package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
// ★ JsonArrayBuilder.add(String) 是**扩展函数**，必须显式 import；
//   缺了它，Kotlin 只会看到成员函数 add(JsonElement)，于是 add("文本") 报类型不符。
//   （CalculatorTool 用的是 `import kotlinx.serialization.json.*` 通配符，所以没暴露这个问题。）
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

private const val TAG = "GitTools"

/** 公共下载目录（与 FileTools / DownloadTools 一致） */
private const val PUBLIC_ROOT = "/storage/emulated/0/Download"

/** 「编程开发工具」下的仓库默认存放目录名（位于下载根目录下） */
private const val REPOS_SUBDIR = "repos"

/** 未配置 git 身份时使用的默认值 */
private const val DEFAULT_USER_NAME = "RikkaSharp"
private const val DEFAULT_USER_EMAIL = "rikkasharp@localhost"

/**
 * 真正的 git 工具（基于 JGit，不是快照下载）。
 *
 * ── 与 `repo_download` 的区别 ──
 * `repo_download` 拿的是某分支的 tarball 快照（**没有 .git 目录**，不能提交/看历史）；
 * 本组工具用 JGit 做**真正的 git 操作**：克隆（含历史）、查看状态与差异、提交、分支、推送/拉取。
 *
 * ── 在 Android 上的两个注意点 ──
 * 1. **git 身份**：JGit 提交时要求 author/committer 的 name/email。
 *    Android 上没有全局 git 配置，故代码里会在缺失时**自动写入默认值**，
 *    避免用户第一次提交就撞上 "No identity" 之类的错误。
 * 2. **仓库位置**：默认放在下载根目录下的 `repos/`，让用户能在文件管理器里找到。
 *    若公共目录不可写（未授予「所有文件访问权限」），回退到 App 私有目录。
 *
 * ── 凭据 ──
 * 私有仓库的 push/pull 需要 token，通过**工具参数**传入（HTTPS 方式）。
 * 不写入任何日志，也不持久化。
 */
fun createGitTools(context: Context): List<Tool> = listOf(
    gitCloneTool(context),
    gitStatusTool(context),
    gitLogTool(context),
    gitDiffTool(context),
    gitCommitTool(context),
    gitBranchTool(context),
    gitSyncTool(context),
)

// ────────────────────────── 公共辅助 ──────────────────────────

/**
 * 解析下载根目录。
 *
 * 与 `DownloadTools.resolveRootDir` 同样的策略（优先公共 Download，不可写则回退私有目录）。
 * 这里**独立实现**而不去复用，是为了不触碰已经验证过的下载工具代码。
 */
private fun resolveRoot(context: Context): File {
    val public = File(PUBLIC_ROOT)
    val ok = runCatching { public.mkdirs(); public.isDirectory && public.canWrite() }.getOrDefault(false)
    return if (ok) public else File(context.filesDir, "downloads").apply { mkdirs() }
}

/** 把用户给的路径解析成仓库目录；相对路径按下载根目录处理 */
private fun resolveRepoDir(context: Context, path: String): File {
    val f = File(path)
    return if (f.isAbsolute) f else File(resolveRoot(context), path)
}

/** 打开一个已有仓库（校验 .git 是否存在，给出可读错误） */
private fun openRepo(context: Context, path: String): Pair<Git, File> {
    val dir = resolveRepoDir(context, path)
    if (!File(dir, ".git").exists()) {
        throw IllegalArgumentException(
            "不是 git 仓库：${dir.absolutePath}\n" +
                "（该目录下没有 .git。若你只有下载来的快照代码，请先用 git_clone 克隆）"
        )
    }
    return Git.open(dir) to dir
}

/** 缺省身份时补上，避免 commit 因 "No identity" 失败 */
private fun ensureIdentity(git: Git, name: String?, email: String?) {
    val config = git.repository.config
    val n = config.getString("user", null, "name") ?: name ?: DEFAULT_USER_NAME
    val e = config.getString("user", null, "email") ?: email ?: DEFAULT_USER_EMAIL
    if (config.getString("user", null, "name") == null) {
        config.setString("user", null, "name", n)
    }
    if (config.getString("user", null, "email") == null) {
        config.setString("user", null, "email", e)
    }
    runCatching { config.save() }
}

private fun creds(token: String?) =
    token?.takeIf { it.isNotBlank() }?.let { UsernamePasswordCredentialsProvider(it, "") }

/** 文本结果（统一走 UIMessagePart.Text，与其它工具一致） */
private fun text(json: kotlinx.serialization.json.JsonObject) =
    listOf(UIMessagePart.Text(json.toString()))

/** 限制字符串长度，避免把整个文件塞进上下文 */
private fun String.cap(max: Int = 4000): String =
    if (length <= max) this else substring(0, max) + "\n…（已截断，共 $length 字符）"

// ────────────────────────── 1. clone ──────────────────────────

private fun gitCloneTool(context: Context): Tool = Tool(
    name = "git_clone",
    description = """
        Clone a git repository with full history (real git, via JGit) into a local directory.
        Unlike repo_download (which fetches a tarball snapshot with no .git), the result is a working
        repository you can commit to, branch, and push.
        For large repositories prefer depth=1 (shallow) to keep the download small.
        If the operation exceeds the tool timeout, clone again later — partially cloned directories
        are left in place and git will refuse to reuse them; delete the directory first.
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Repository URL, e.g. https://github.com/owner/repo.git")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Destination directory. Relative paths go under the download root's '$REPOS_SUBDIR'. " +
                        "Defaults to <repos>/<repo-name>.")
                })
                put("branch", buildJsonObject {
                    put("type", "string")
                    put("description", "Branch or tag to check out after cloning")
                })
                put("depth", buildJsonObject {
                    put("type", "integer")
                    put("description", "Shallow clone depth. 1 = only the latest commit (much faster). Omit for full history.")
                })
            },
            required = listOf("url"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val url = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("url is required")
        val branch = obj["branch"]?.jsonPrimitive?.contentOrNull
        val depth = obj["depth"]?.jsonPrimitive?.longOrNull?.toInt()?.takeIf { it > 0 }

        val repoName = url.substringBefore('?').substringAfterLast('/')
            .removeSuffix(".git").ifBlank { "repo" }
        val pathArg = obj["path"]?.jsonPrimitive?.contentOrNull
        val dest = when {
            pathArg.isNullOrBlank() -> File(resolveRoot(context), "$REPOS_SUBDIR/$repoName")
            File(pathArg).isAbsolute -> File(pathArg)
            else -> File(resolveRoot(context), pathArg)
        }
        if (dest.exists() && dest.list()?.isNotEmpty() == true) {
            throw IllegalArgumentException(
                "目标目录非空：${dest.absolutePath}\n" +
                    "git 不会克隆到非空目录。请改用别的 path，或先删除该目录。"
            )
        }

        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            val cmd = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dest)
                .setCloneAllBranches(depth == null)
            branch?.takeIf { it.isNotBlank() }?.let { cmd.setBranch(it) }
            depth?.let { cmd.setDepth(it) }
            cmd.call().use { git ->
                val head = git.repository.exactRef("HEAD")?.objectId?.name?.take(10) ?: "?"
                Log.i(TAG, "cloned $url -> ${dest.absolutePath}")
                text(buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("path", JsonPrimitive(dest.absolutePath))
                    put("head", JsonPrimitive(head))
                    put("shallow", JsonPrimitive(depth != null))
                })
            }
        }
    },
)

// ────────────────────────── 2. status ──────────────────────────

private fun gitStatusTool(context: Context): Tool = Tool(
    name = "git_status",
    description = "Show the working tree status of a local repository: current branch, staged/added/modified/" +
        "untracked files, and how far the branch is ahead/behind its upstream.",
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("repo", buildJsonObject {
                    put("type", "string")
                    put("description", "Repository directory (relative to download root, or absolute)")
                })
            },
            required = listOf("repo"),
        )
    },
    execute = { args ->
        val repo = args.jsonObject["repo"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("repo is required")
        withContext(Dispatchers.IO) {
            openRepo(context, repo).let { (git, dir) ->
                git.use { g ->
                    val st = g.status().call()
                    text(buildJsonObject {
                        put("path", JsonPrimitive(dir.absolutePath))
                        put("branch", JsonPrimitive(g.repository.branch ?: "(detached)"))
                        put("clean", JsonPrimitive(st.isClean))
                        put("added", buildJsonArray { st.added.forEach { add(it) } })
                        put("changed", buildJsonArray { st.changed.forEach { add(it) } })
                        put("modified", buildJsonArray { st.modified.forEach { add(it) } })
                        put("missing", buildJsonArray { st.missing.forEach { add(it) } })
                        put("untracked", buildJsonArray { st.untracked.forEach { add(it) } })
                        put("conflicting", buildJsonArray { st.conflicting.forEach { add(it) } })
                    })
                }
            }
        }
    },
)

// ────────────────────────── 3. log ──────────────────────────

private fun gitLogTool(context: Context): Tool = Tool(
    name = "git_log",
    description = "Show recent commits of a local repository (hash, author, date, message).",
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("repo", buildJsonObject {
                    put("type", "string")
                    put("description", "Repository directory")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "How many commits to list (default 20, max 200)")
                })
            },
            required = listOf("repo"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val repo = obj["repo"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("repo is required")
        val limit = (obj["limit"]?.jsonPrimitive?.longOrNull?.toInt() ?: 20).coerceIn(1, 200)
        withContext(Dispatchers.IO) {
            openRepo(context, repo).let { (git, _) ->
                git.use { g ->
                    val commits = g.log().setMaxCount(limit).call().toList()
                    text(buildJsonObject {
                        put("count", JsonPrimitive(commits.size))
                        put("commits", buildJsonArray {
                            commits.forEach { c ->
                                add(buildJsonObject {
                                    put("hash", JsonPrimitive(c.name.take(10)))
                                    put("author", JsonPrimitive(c.authorIdent.name))
                                    put("email", JsonPrimitive(c.authorIdent.emailAddress))
                                    put("when", JsonPrimitive(c.authorIdent.getWhen().toInstant().toString()))
                                    put("message", JsonPrimitive(c.fullMessage.trim().cap(500)))
                                })
                            }
                        })
                    })
                }
            }
        }
    },
)

// ────────────────────────── 4. diff ──────────────────────────

private fun gitDiffTool(context: Context): Tool = Tool(
    name = "git_diff",
    description = "Show the diff of a local repository. By default shows unstaged changes; " +
        "set staged=true to see what is about to be committed.",
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("repo", buildJsonObject {
                    put("type", "string")
                    put("description", "Repository directory")
                })
                put("staged", buildJsonObject {
                    put("type", "boolean")
                    put("description", "true = diff of the index (staged), false = working tree (default)")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional file path filter")
                })
            },
            required = listOf("repo"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val repo = obj["repo"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("repo is required")
        val staged = obj["staged"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val path = obj["path"]?.jsonPrimitive?.contentOrNull
        withContext(Dispatchers.IO) {
            openRepo(context, repo).let { (git, _) ->
                git.use { g ->
                    val cmd = g.diff().setCached(staged)
                    // setPathFilter 只接受 PathFilter，没有 String 重载
                    path?.takeIf { it.isNotBlank() }?.let { cmd.setPathFilter(PathFilter.create(it)) }
                    val diff = cmd.call()
                    text(buildJsonObject {
                        put("staged", JsonPrimitive(staged))
                        put("files", JsonPrimitive(diff.size))
                        put("diff", JsonPrimitive(diff.toString().cap(8000)))
                    })
                }
            }
        }
    },
)

// ────────────────────────── 5. commit ──────────────────────────

private fun gitCommitTool(context: Context): Tool = Tool(
    name = "git_commit",
    description = """
        Stage files and create a commit in a local repository.
        By default stages everything (add -A equivalent). Pass paths to stage only specific files.
        The commit does NOT push — use git_sync with action="push" for that.
        Author identity defaults to a generic name if the repository has none configured.
    """.trimIndent().replace("\n", " "),
    // 提交会改动本地仓库，但不会影响设备或远程；与 shell/ssh 相比风险有限，默认不要求授权
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("repo", buildJsonObject {
                    put("type", "string")
                    put("description", "Repository directory")
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "Commit message")
                })
                put("paths", buildJsonObject {
                    put("type", "array")
                    put("description", "Optional list of file paths to stage. Omit to stage all changes.")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("author_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional author name (otherwise repo config or a default)")
                })
                put("author_email", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional author email")
                })
            },
            required = listOf("repo", "message"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val repo = obj["repo"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("repo is required")
        val message = obj["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("message is required")
        val paths = (obj["paths"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.filter { it.isNotBlank() }.orEmpty()
        val name = obj["author_name"]?.jsonPrimitive?.contentOrNull
        val email = obj["author_email"]?.jsonPrimitive?.contentOrNull

        withContext(Dispatchers.IO) {
            openRepo(context, repo).let { (git, _) ->
                git.use { g ->
                    ensureIdentity(g, name, email)
                    val add = g.add()
                    if (paths.isEmpty()) add.addFilepattern(".") else paths.forEach { add.addFilepattern(it) }
                    add.call()

                    val st = g.status().call()
                    if (st.isClean) {
                        return@withContext text(buildJsonObject {
                            put("ok", JsonPrimitive(false))
                            put("reason", JsonPrimitive("没有需要提交的改动（工作区干净）"))
                        })
                    }

                    val rev = g.commit().setMessage(message).call()
                    text(buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("hash", JsonPrimitive(rev.name.take(10)))
                        put("message", JsonPrimitive(rev.fullMessage.trim()))
                        // parentCount 是**父提交数**（0=初始提交，1=普通提交，>1=合并提交），
                        // 不是改动文件数 —— 先前我误标成 files，已修正
                        put("parents", JsonPrimitive(rev.parentCount))
                    })
                }
            }
        }
    },
)

// ────────────────────────── 6. branch ──────────────────────────

private fun gitBranchTool(context: Context): Tool = Tool(
    name = "git_branch",
    description = """
        Branch operations on a local repository.
        action="list" (default): list local branches with the current one marked.
        action="create": create a branch with name (and switch to it unless checkout=false).
        action="checkout": switch to an existing branch or tag given by name.
        action="delete": delete the branch given by name.
    """.trimIndent().replace("\n", " "),
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("repo", buildJsonObject { put("type", "string"); put("description", "Repository directory") })
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "list | create | checkout | delete")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Branch (or tag) name — required for create/checkout/delete")
                })
                put("checkout", buildJsonObject {
                    put("type", "boolean")
                    put("description", "For action=create: switch to the new branch (default true)")
                })
            },
            required = listOf("repo"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val repo = obj["repo"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("repo is required")
        val action = obj["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        withContext(Dispatchers.IO) {
            openRepo(context, repo).let { (git, _) ->
                git.use { g ->
                    when (action) {
                        "list" -> {
                            val cur = g.repository.branch
                            val branches = g.branchList().call().map {
                                org.eclipse.jgit.lib.Repository.shortenRefName(it.name)
                            }
                            text(buildJsonObject {
                                put("current", JsonPrimitive(cur ?: "(detached)"))
                                put("branches", buildJsonArray {
                                    branches.forEach { b ->
                                        add(buildJsonObject {
                                            put("name", JsonPrimitive(b))
                                            put("current", JsonPrimitive(b == cur))
                                        })
                                    }
                                })
                            })
                        }
                        "create" -> {
                            val n = name ?: error("name is required for action=create")
                            g.branchCreate().setName(n).call()
                            val switch = obj["checkout"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                            if (switch) g.checkout().setName(n).call()
                            text(buildJsonObject {
                                put("ok", JsonPrimitive(true))
                                put("created", JsonPrimitive(n))
                                put("switched", JsonPrimitive(switch))
                            })
                        }
                        "checkout" -> {
                            val n = name ?: error("name is required for action=checkout")
                            g.checkout().setName(n).call()
                            text(buildJsonObject {
                                put("ok", JsonPrimitive(true))
                                put("checked_out", JsonPrimitive(n))
                            })
                        }
                        "delete" -> {
                            val n = name ?: error("name is required for action=delete")
                            val deleted = g.branchDelete().setBranchNames(n).call()
                            text(buildJsonObject {
                                put("ok", JsonPrimitive(true))
                                put("deleted", buildJsonArray { deleted.forEach { add(it) } })
                            })
                        }
                        else -> error("未知 action：$action（支持 list / create / checkout / delete）")
                    }
                }
            }
        }
    },
)

// ────────────────────────── 7. pull / push ──────────────────────────

private fun gitSyncTool(context: Context): Tool = Tool(
    name = "git_sync",
    description = """
        Pull from or push to the repository's remote (HTTPS).
        action="pull": fetch + merge the upstream branch.
        action="push": push the current branch to origin.
        For private repositories pass a personal access token; it is used only for this call and never logged.
        To set an upstream tracking branch, push once with the remote/branch you want (JGit's PushCommand
        has no direct "set upstream" switch).
        Note: HTTPS remotes only — for SSH remotes use the SSH tools on a host that has git installed.
    """.trimIndent().replace("\n", " "),
    // 会改动远程仓库（push），属于需要谨慎的操作 —— 但用户未要求对它单独授权，
    // 且需显式传 action="push"，故保持默认（不强制授权）。如需可后续加入 LocalToolDefaultApprovals。
    needsApproval = { false },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("repo", buildJsonObject { put("type", "string"); put("description", "Repository directory") })
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "pull | push")
                })
                put("token", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional HTTPS access token for private repositories")
                })
                put("remote", buildJsonObject {
                    put("type", "string")
                    put("description", "Remote name (default origin)")
                })
                put("branch", buildJsonObject {
                    put("type", "string")
                    put("description", "Remote branch name (optional)")
                })
            },
            required = listOf("repo", "action"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val repo = obj["repo"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("repo is required")
        val action = obj["action"]?.jsonPrimitive?.contentOrNull?.lowercase()
            ?: error("action is required (pull | push)")
        val token = obj["token"]?.jsonPrimitive?.contentOrNull
        val remote = obj["remote"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "origin"
        val branch = obj["branch"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val c = creds(token)

        withContext(Dispatchers.IO) {
            openRepo(context, repo).let { (git, _) ->
                git.use { g ->
                    when (action) {
                        "pull" -> {
                            val cmd = g.pull().setRemote(remote)
                            c?.let { cmd.setCredentialsProvider(it) }
                            branch?.let { cmd.setRemoteBranchName(it) }
                            val result = cmd.call()
                            text(buildJsonObject {
                                put("ok", JsonPrimitive(result.isSuccessful))
                                put("action", JsonPrimitive("pull"))
                                put("remote", JsonPrimitive(remote))
                                put("message", JsonPrimitive(result.mergeResult?.mergeStatus?.toString() ?: "ok"))
                            })
                        }
                        "push" -> {
                            val cmd = g.push().setRemote(remote)
                            c?.let { cmd.setCredentialsProvider(it) }
                            val result = cmd.call()
                            text(buildJsonObject {
                                put("ok", JsonPrimitive(true))
                                put("action", JsonPrimitive("push"))
                                put("remote", JsonPrimitive(remote))
                                put("results", buildJsonArray {
                                    result.forEach { r ->
                                        add(buildJsonObject {
                                            // ★ 用 getRemoteUpdates()（PushResult 只暴露这一个）：
                                            //   先前我写的 r.messages 并不存在（PushResult 没有 getMessages），
                                            //   那会让类型推断失败，并连带报 joinToString 找不到。
                                            // RemoteRefUpdate 的远程引用名是 getRemoteName()。
                                            put("remote_refs", JsonPrimitive(
                                                r.remoteUpdates.joinToString(", ") { it.remoteName ?: "?" }
                                            ))
                                            // 状态很有用：OK / UP_TO_DATE / REJECTED / NON_EXISTING 等
                                            put("statuses", JsonPrimitive(
                                                r.remoteUpdates.joinToString(", ") { it.status.toString() }
                                            ))
                                        })
                                    }
                                })
                            })
                        }
                        else -> error("未知 action：$action（支持 pull / push）")
                    }
                }
            }
        }
    },
)
