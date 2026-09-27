// sherpa-onnx 底层验证程序 —— 用于判断「播放闪退」是 App 层问题还是底层问题
//
// 用法: LD_LIBRARY_PATH=. ./test_sherpa <模型目录> [并发线程数]
// 例:   LD_LIBRARY_PATH=. ./test_sherpa /path/to/vits-icefall-zh-aishell3
//
// 阶段 1：单线程创建 + 合成（确认 .so 与模型基本可用）
// 阶段 2：N 线程并发对**同一个** OfflineTts 调用 Generate
//         —— 复现 Rikka# 中 TtsController.prefetchCount=4 的场景，
//            验证「并发使用同一实例」是否会崩溃。
//
// 每一步都 fflush，确保崩溃时能看到进行到哪一步。

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include "c-api.h"

static const SherpaOnnxOfflineTts *g_tts = NULL;
static const char *g_text = "你好，这是一段测试语音。";

struct ThreadArg {
    int sid;
    int ok;
    int n_samples;
};

static void *worker(void *p) {
    struct ThreadArg *a = (struct ThreadArg *)p;
    const SherpaOnnxGeneratedAudio *audio =
        SherpaOnnxOfflineTtsGenerate(g_tts, g_text, a->sid, 1.0f);
    if (audio) {
        a->ok = 1;
        a->n_samples = audio->n;
        SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio);
    } else {
        a->ok = 0;
    }
    return NULL;
}

int main(int argc, char **argv) {
    const char *dir = argc > 1
        ? argv[1]
        : "/storage/emulated/0/Download/tts_models/vits-icefall-zh-aishell3";
    int nthreads = argc > 2 ? atoi(argv[2]) : 4;
    int onnx_threads = argc > 3 ? atoi(argv[3]) : 1;   // App 默认为 2
    const char *rule_fsts = argc > 4 ? argv[4] : "";   // App 会传 4 个 .fst

    char model[1024], tokens[1024], lexicon[1024];
    snprintf(model, sizeof(model), "%s/model.onnx", dir);
    snprintf(tokens, sizeof(tokens), "%s/tokens.txt", dir);
    snprintf(lexicon, sizeof(lexicon), "%s/lexicon.txt", dir);

    printf("=== sherpa-onnx 底层验证 ===\n");
    printf("[0] 模型目录: %s\n", dir);
    fflush(stdout);

    SherpaOnnxOfflineTtsConfig cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.model.vits.model = model;
    cfg.model.vits.tokens = tokens;
    cfg.model.vits.lexicon = lexicon;
    cfg.model.vits.noise_scale = 0.667f;
    cfg.model.vits.noise_scale_w = 0.8f;
    cfg.model.vits.length_scale = 1.0f;
    cfg.model.num_threads = onnx_threads;
    cfg.model.debug = 0;
    cfg.model.provider = "cpu";
    cfg.rule_fsts = rule_fsts;
    cfg.rule_fars = "";          // 与 App 修复后一致：不加载 173MB 的 rule.far
    cfg.max_num_sentences = 1;
    cfg.silence_scale = 0.2f;

    printf("[1] 正在创建 OfflineTts（加载 onnx）...\n");
    fflush(stdout);
    g_tts = SherpaOnnxCreateOfflineTts(&cfg);
    if (!g_tts) {
        printf("!! [1] 创建失败（返回 NULL）—— .so 或模型有问题\n");
        return 2;
    }
    printf("[1] 创建成功 ✓\n");
    fflush(stdout);

    int sr = SherpaOnnxOfflineTtsSampleRate(g_tts);
    int ns = SherpaOnnxOfflineTtsNumSpeakers(g_tts);
    printf("[2] sampleRate=%d  numSpeakers=%d\n", sr, ns);
    printf("[2b] onnx_threads=%d  rule_fsts=%s\n", onnx_threads,
           (rule_fsts && rule_fsts[0]) ? rule_fsts : "(空)");
    fflush(stdout);

    printf("[3] 单线程合成 ...\n");
    fflush(stdout);
    const SherpaOnnxGeneratedAudio *audio =
        SherpaOnnxOfflineTtsGenerate(g_tts, g_text, 0, 1.0f);
    if (!audio) {
        printf("!! [3] 合成返回 NULL\n");
        SherpaOnnxDestroyOfflineTts(g_tts);
        return 3;
    }
    printf("[3] 合成成功 ✓ n=%d  sampleRate=%d  时长=%.2fs\n",
           audio->n, audio->sample_rate,
           audio->sample_rate > 0 ? (double)audio->n / audio->sample_rate : 0.0);
    fflush(stdout);
    SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio);

    if (nthreads <= 1) {
        printf("[4] 跳过并发测试（nthreads=%d）\n", nthreads);
        SherpaOnnxDestroyOfflineTts(g_tts);
        printf("=== 全部通过 ✓ ===\n");
        return 0;
    }

    printf("[4] 并发测试：%d 个线程同时对同一实例调用 Generate ...\n", nthreads);
    fflush(stdout);
    pthread_t th[32];
    struct ThreadArg args[32];
    if (nthreads > 32) nthreads = 32;
    for (int i = 0; i < nthreads; i++) {
        args[i].sid = i % (ns > 0 ? ns : 1);
        args[i].ok = 0;
        args[i].n_samples = 0;
    }
    for (int i = 0; i < nthreads; i++) {
        if (pthread_create(&th[i], NULL, worker, &args[i]) != 0) {
            printf("!! pthread_create 失败 at %d\n", i);
            return 4;
        }
    }
    for (int i = 0; i < nthreads; i++) pthread_join(th[i], NULL);

    int okc = 0;
    for (int i = 0; i < nthreads; i++) if (args[i].ok) okc++;
    printf("[4] 并发完成：%d/%d 成功\n", okc, nthreads);
    fflush(stdout);

    SherpaOnnxDestroyOfflineTts(g_tts);
    printf("=== 全部通过 ✓ ===\n（若此程序未崩溃，说明并发在底层是可用的，问题在 App 层）\n");
    return 0;
}
