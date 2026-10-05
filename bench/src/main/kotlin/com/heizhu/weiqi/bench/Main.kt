package com.heizhu.weiqi.bench

import com.heizhu.weiqi.core.ai.Difficulty
import com.heizhu.weiqi.core.ai.MctsEngine
import com.heizhu.weiqi.core.ai.PolicyValueNet
import com.heizhu.weiqi.core.game.GameState
import com.heizhu.weiqi.core.game.MoveOutcome
import java.io.File
import com.heizhu.weiqi.core.rules.Board
import com.heizhu.weiqi.core.rules.PlayOutcome
import com.heizhu.weiqi.core.rules.Stone
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.random.Random

/**
 * 棋力评测台入口。
 *
 * 设计取舍：**不做 GTP 协议**。评测只需要「给局面 → 要一手」这一件事，
 * 而 KataGo 的分析引擎本身也是 JSON 进出 —— 两边对称，Python 侧驱动最省事。
 * 多实现一套 GTP 协议，只会给评测流程多增加一个与棋力无关的失败点。
 *
 * 协议：stdin 一行一条命令，stdout 一行一条结果（Python 直接按行配对即可）。
 *
 *     genmove <size> <B|W> <difficultyId> <seed> <moves>
 *         → "x,y"          这一手
 *         → "pass"         停一手
 *     selfplay <size> <difficultyId> <plies> <seed>
 *         → 空格分隔的落子序列，如 "3,3 4,5 pass ..."（黑先、交替）
 *     quit
 *
 * `<moves>` 用空格分隔，元素为 "x,y"，停一手写作 `pass`，空局面写作 `-`。
 * 坐标是**扁平索引的分解**：index = y * size + x（与 app 内部一致）。
 */
fun main() {
    val out = java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true)
    val reader = BufferedReader(InputStreamReader(System.`in`))
    while (true) {
        val line = reader.readLine() ?: break
        val t = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (t.isEmpty()) continue
        try {
            when (t[0]) {
                "quit" -> return
                "genmove" -> out.println(genmove(t))
                "selfplay" -> out.println(selfplay(t))
                "appmatch" -> out.println(appMatch(t))
                "netbench" -> out.println(netBench(t))
                "netcheck" -> out.println(netCheck(t))
                "score" -> out.println(scoreGame(t))
                else -> out.println("err unknown-command ${t[0]}")
            }
        } catch (e: Exception) {
            // 出错必须**把错误回给调用方**而不是静默断流：评测台悄悄少一条结果，
            // 统计会拿到错位的配对，出来的棋力数字全是假的。
            out.println("err ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

/** 按交替颜色把着法序列摆回棋盘。非法着法直接抛，不静默跳过 —— 局面喂错等于白测。 */
private fun parseBoard(size: Int, movesSpec: String): Board {
    val board = Board(size)
    var color = Stone.BLACK
    for (m in movesSpec.split(' ')) {
        if (m.isEmpty() || m == "-") continue
        if (m == "pass") {
            board.pass()
        } else {
            val parts = m.split(',')
            val x = parts[0].toInt()
            val y = parts[1].toInt()
            val outcome = board.play(x, y, color)
            check(outcome is PlayOutcome.Ok) { "illegal move $m for $color" }
        }
        color = color.opponent
    }
    return board
}

/**
 * 解析难度，**不认识的直接报错**。
 *
 * 不能用 Difficulty.fromId：它找不到时会静默回退到 BEGINNER（那是为「存档损坏」准备的
 * 容错）。评测里这个兜底极其危险 —— 我传了 "ENTRY"（大写），fromId 匹配的是小写 id
 * "entry"，于是五次「不同难度」其实全跑了 BEGINNER，得出「难度旋钮失效」的假结论，
 * 而且任何地方都不会报错。
 */
/**
 * 可选的蒸馏网络。用环境变量 WEIQI_NET 指向 `.bin`（同目录同名 `.json` 是 manifest）。
 *
 * 设了它，评测跑的就是「网络引导」路径；不设则跑原来的随机 rollout 路径 ——
 * 这样同一套评测台能把两条路径放在同一批局面上对照，这是验收的核心手段。
 */
private fun loadNet(envVar: String = "WEIQI_NET"): PolicyValueNet? {
    val path = System.getenv(envVar) ?: return null
    val bin = File(path)
    val manifest = File(path.removeSuffix(".bin") + ".json")
    check(bin.isFile) { "$envVar 指向的文件不存在：$path" }
    check(manifest.isFile) { "缺少 manifest：${manifest.path}" }
    val net = PolicyValueNet.load(manifest.readText(), bin.readBytes())
    System.err.println("已加载网络 $path（${bin.length() / 1024} KB）")
    return net
}

private fun difficultyOf(raw: String): Difficulty {
    val id = raw.lowercase()
    return Difficulty.entries.firstOrNull { it.id == id }
        ?: error("未知难度 '$raw'，可用: ${Difficulty.entries.joinToString(", ") { it.id }}")
}

/**
 * 电视实测速率（模拟次数/秒）。设了它，就把各档的模拟次数封到「预算 × 速率」，
 * 复现电视上的真实条件；不设则用各档自己的 maxPlayouts（开发机的满速跑法）。
 *
 * 用途：开发机比电视快 5~6 倍，不封顶的话评测出来的棋力会偏乐观。
 */
private fun tvPlayoutCap(difficulty: Difficulty): Int? {
    val rate = System.getenv("WEIQI_TV_RATE")?.toDoubleOrNull() ?: return null
    return (difficulty.timeBudgetMs * rate / 1000.0).toInt().coerceAtLeast(1)
}

/**
 * 跨实现一致性检查：`netcheck <size> <toMove> <cells>`
 *   cells = 逗号分隔的 size*size 个 0/1/2（与 Board.cells 同序）
 *
 * 输出网络输出摘要（策略和 / 价值 / 前 10 个策略值），供与 PyTorch 侧逐项比对。
 *
 * ⚠️ 为什么必须有：端侧手写前向曾**漏写一个 relu** 导致输出全错 ——
 * 权重与 manifest 逐字节一致、残差块也都在，肉眼根本看不出来，
 * 最后靠"把 Kotlin 逐行搬成 Python、对同一输入跑前向比输出"才定位到。
 * 凡改动**输入构造**（如本轮视角统一）或前向实现，必须重跑本检查。
 */
private fun netCheck(t: List<String>): String {
    val size = t[1].toInt()
    val toMove = t[2].toByte()
    val cells = t[3].split(",").map { it.trim().toInt().toByte() }.toByteArray()
    val net = loadNet() ?: return "err 没有设置 WEIQI_NET"
    val e = net.evaluate(cells, toMove)
    val top = e.policy.withIndex().sortedByDescending { it.value }.take(10)
        .joinToString(" ") { "${it.index}:${"%.6f".format(it.value)}" }
    return "sum=${"%.6f".format(e.policy.sum())} value=${"%.6f".format(e.scoreLead)} top=$top"
}

/**
 * 量网络前向耗时：`netbench <size> <iters>`。
 *
 * 为什么单独量：整条路线的端侧可行性**只取决于这个数字** ——
 * 电视上一次前向若 60ms，6 秒预算只够 100 次，路线就得重设计。
 * 单独量出来才能早决定，而不是等全部做完才发现跑不动。
 */
private fun netBench(t: List<String>): String {
    val size = t[1].toInt()
    val iters = t.getOrNull(2)?.toIntOrNull() ?: 50
    val net = loadNet() ?: return "err 没有设置 WEIQI_NET"
    val cells = ByteArray(size * size)
    // 造一个有几颗子的局面，避免空盘特例
    cells[size * size / 2] = 1
    cells[size * size / 2 + 1] = 2
    // 预热（首次调用要初始化 JIT/缓存，不预热会把首帧算进去）
    repeat(3) { net.evaluate(cells, 1) }
    val t0 = System.nanoTime()
    repeat(iters) { net.evaluate(cells, 1) }
    val ms = (System.nanoTime() - t0) / 1_000_000.0 / iters
    return "size=$size iters=$iters 前向 %.3f ms/次".format(ms)
}

/**
 * 终局数子：`score <size> <moves...>` → `黑方净胜子数 winner=B|W`。
 *
 * 为什么评测台需要它：对局裁判原本只用 KataGo 分析终局，但 KataGo 会**拒绝**某些
 * 棋谱 —— 我的引擎用简单劫，KataGo 默认带超级劫，劫争反复时它认为非法。
 * 于是整局成绩算不出来。用本引擎自己的数子器兜底，至少能把对局判出来。
 *
 * ⚠️ 局限：本数子器不判死子（见 core 的 Scorer 注释）。收官阶段盘面若有未提的死子，
 * 会判偏。所以**优先用 KataGo 当裁判**，只在它拒绝时才退回这里。
 */
private fun scoreGame(t: List<String>): String {
    val size = t[1].toInt()
    val board = parseBoard(size, if (t.size > 2) t.drop(2).joinToString(" ") else "-")
    val score = com.heizhu.weiqi.core.rules.Scorer(size)
        .score(board.cells, com.heizhu.weiqi.core.rules.Komi.forSize(size))
    val margin = score.blackMargin
    val blackWins = margin > 2 * score.komi
    return "blackMargin=$margin komi=${score.komi} winner=${if (blackWins) "B" else "W"}"
}

private fun genmove(t: List<String>): String {
    val size = t[1].toInt()
    val color = if (t[2].uppercase().startsWith("B")) Stone.BLACK else Stone.WHITE
    val diff = difficultyOf(t[3])
    val seed = t.getOrNull(4)?.toLongOrNull() ?: 1L
    // ⚠️ 着法序列是**整行剩余部分**，不能只取 t[5]。
    // 只取第一个 token 时，引擎会在几乎空盘上选点（合法），而调用方以为送的是完整局面，
    // 现象是 KataGo 报「非法着法」—— 排查方向会被带偏到引擎身上（实际是我的解析丢了着法）。
    val spec = if (t.size > 5) t.drop(5).joinToString(" ") else "-"
    val board = parseBoard(size, spec)
    val cap = tvPlayoutCap(diff)
    val engine = MctsEngine(size, net = loadNet(), judgeNet = loadNet("WEIQI_JUDGE_NET"))
    // 可选：用环境变量覆盖「AI 最长思考时间」（对应 app 设置里那一项）。
    // 有了它才能**实测**那个设置是否真的改变搜索量 —— 否则只能在电视上靠感觉。
    val budgetMs = System.getenv("WEIQI_THINK_MS")?.toLongOrNull()
    val move = runBlocking {
        engine.findBestMove(board, color, diff, Random(seed), cap, timeBudgetOverrideMs = budgetMs)
    }
    return if (move < 0) "pass" else "${move % size},${move / size}"
}

private fun selfplay(t: List<String>): String {
    val size = t[1].toInt()
    val diff = difficultyOf(t[2])
    val plies = t[3].toInt()
    val seed = t.getOrNull(4)?.toLongOrNull() ?: 1L
    val engine = MctsEngine(size, net = loadNet(), judgeNet = loadNet("WEIQI_JUDGE_NET"))
    val board = Board(size)
    var color = Stone.BLACK
    val moves = ArrayList<String>(plies)
    val cap = tvPlayoutCap(diff)
    for (i in 0 until plies) {
        // 每手换种子：定长种子的 MCTS 会走出完全一样的棋，样本就没有多样性了
        val move = runBlocking { engine.findBestMove(board, color, diff, Random(seed + i), cap) }
        if (move < 0) {
            board.pass()
            moves += "pass"
        } else {
            val x = move % size
            val y = move / size
            check(board.play(x, y, color) is PlayOutcome.Ok) { "selfplay produced illegal move $x,$y" }
            moves += "$x,$y"
        }
        color = color.opponent
    }
    return moves.joinToString(" ")
}

/**
 * **"醉汉模拟"档**：完全不懂围棋的对手 —— 见子就吃，否则随机落子。
 *
 * 为什么需要它（2026-10-05）：用户反馈"一个不会下围棋的醉汉都能赢 9 路中级"。
 * 而该档对 KataGo 每手只丢 1.5 目（≈业余低段），照理不该被新手赢。
 * ⇒ 说明存在"行为缺陷"而非棋力不足。这个档用来把缺陷暴露出来：
 *   如果随机乱下 + 贪婪吃子能赢 AI，就说明 AI 在某些局面会白送大块。
 *
 * 返回 -1 表示停一手（沿用引擎的约定）。
 */
private fun drunkardMove(state: GameState, rng: java.util.Random): Int {
    val size = state.size
    val color = state.toMove
    val empties = (0 until size * size).filter { state.board.cells[it].toInt() == 0 }
    if (empties.isEmpty()) return -1

    // 试探性落子：返回"提掉的子数"，-1 表示非法（自杀/劫）
    fun tryPlay(idx: Int): Int {
        val probe = Board(size)
        probe.restoreFrom(state.board.cells, state.board.koPoint, state.board.lastMove,
            state.board.blackCaptured, state.board.whiteCaptured)
        val before = if (color == Stone.BLACK) probe.whiteCaptured else probe.blackCaptured
        if (probe.play(idx % size, idx / size, color) !is PlayOutcome.Ok) return -1
        val after = if (color == Stone.BLACK) probe.whiteCaptured else probe.blackCaptured
        return after - before
    }

    // ⚠️ 必须只选合法点：否则一遇到自杀点就产生非法手、整个对局被评测台判废
    val legal = empties.filter { tryPlay(it) >= 0 }
    if (legal.isEmpty()) return -1
    val capturing = legal.filter { tryPlay(it) > 0 }        // 新手最直观的行为：见子就吃
    return if (capturing.isNotEmpty()) capturing[rng.nextInt(capturing.size)]
    else legal[rng.nextInt(legal.size)]
}

/**
 * 贴近 app 真实流程的对局探针（与 selfplay 的关键差别）。
 *
 * · 用产品代码的 [GameState] 推进对局 —— 它带着**真实的终局判定**
 *   （双方连续停手 → 数子结算），这正是 selfplay 缺的一环。
 * · 所以它数出来的「停手」「被吃光」是**产品口径**的，不是测量假象。
 *   ⚠️ 2026-10-05 的教训：selfplay 不判终局、一直跑到手数上限，据此数出的
 *   "连停 200 手"把我带偏了一整天 —— 判据口径错了，后面所有改动都是白工。
 *
 * 命令： `appmatch <size> <tierA> <tierB> <seed> [maxPlies]`
 *   网络： A 用 `WEIQI_NET`；B 用 `WEIQI_NET_B`（不设则同 A）；终局裁判用 `WEIQI_JUDGE_NET`
 *   A/B 的执色按 seed 奇偶交替 —— 同档对局两边机会必须均等，否则测不出颜色偏差。
 * 输出： 一行 JSON（结构化，不靠 grep 日志；抓不到就是抓不到，不会静默读空）
 */
private fun appMatch(t: List<String>): String {
    val size = t[1].toInt()
    val tierA = t[2]
    val tierB = t[3]
    // "drunkard" = 醉汉模拟档（见 drunkardMove）：用来验证"不会下棋的人能否赢"
    val aDrunk = tierA == "drunkard"
    val bDrunk = tierB == "drunkard"
    val diffA = if (aDrunk) Difficulty.ENTRY else difficultyOf(tierA)
    val diffB = if (bDrunk) Difficulty.ENTRY else difficultyOf(tierB)
    val seed = t.getOrNull(4)?.toLongOrNull() ?: 1L
    val maxPlies = t.getOrNull(5)?.toIntOrNull() ?: 400
    // 开局随机手数（**测试专用**，见下方注释）。默认 0 = 不随机。
    val openRand = t.getOrNull(6)?.toIntOrNull() ?: 0

    val netA = loadNet()
    val netB = loadNet("WEIQI_NET_B") ?: netA
    val judge = loadNet("WEIQI_JUDGE_NET")

    val aBlack = seed % 2 == 0L
    val komiOverride = t.getOrNull(7)?.toDoubleOrNull()
    // 让子数（t[8]，默认 0）：给**白方**摆 N 颗星位子。因为两边用同一个网络时，
    // 白方胜率就直接读出了「让 N 子」等价多少胜率 —— 这是档位阶梯唯一可靠的标尺，
    // 比"换弱网络/少搜几步"那三条轴的信噪比高一个量级（那三条轴的效应都小于测量噪声）。
    val handicap = t.getOrNull(8)?.toIntOrNull() ?: 0
    val state = if (komiOverride != null) {
        GameState(size, diffA, Stone.WHITE, komi = komiOverride, handicap = handicap)
    } else {
        GameState(size, diffA, Stone.WHITE, handicap = handicap)
    }
    val engineA = MctsEngine(size, net = netA, judgeNet = judge)
    val engineB = MctsEngine(size, net = netB, judgeNet = judge)

    // ⚠️ 开局随机化：档位温度归零（去放水）之后，引擎对同一局面恒走同一手 ——
    // 于是自战 24 局会走出**完全相同的棋**（实测 24 局比分与手数全同），
    // 拿它测"黑白公平性"等于只下了一盘。
    // 开局随机几手即可产生互不相同的对局，而这不改变中后盘的棋力测量口径
    // （产品里对手是孩子，本来就不存在"相同局面"）。
    if (openRand > 0) {
        val r = java.util.Random(seed * 7919L + 13L)
        var played = 0
        var guard = 0
        while (played < openRand && !state.isOver && guard < openRand * 50) {
            guard++
            val legal = (0 until size * size).filter { state.board.cells[it].toInt() == 0 }
            if (legal.isEmpty()) break
            val mv = legal[r.nextInt(legal.size)]
            if (state.play(mv, state.toMove) is MoveOutcome.Ok) {
                played++
            }
        }
    }

    var plies = 0
    var passes = 0
    var consec = 0
    var maxConsec = 0
    var illegal = false
    // 诊断计数（见下方落子处的说明）：验收要能看见"低级错误"，不能只看胜负
    var trueEyeFills = 0     // 填「真眼」的手数（引擎本该排除；非 0 即真错误）
    var enclosedFills = 0    // 四邻全自己的手数（含假眼/补断，合法，仅记录）
    var postPassPlies = 0    // 双方都停过手之后继续下的手数
    while (!state.isOver && plies < maxPlies) {
        val color = state.toMove
        val isA = (color == Stone.BLACK) == aBlack
        val engine = if (isA) engineA else engineB
        val diff = if (isA) diffA else diffB
        val isDrunk = if (isA) aDrunk else bDrunk
        val mv = if (isDrunk) {
            drunkardMove(state, java.util.Random(seed * 1000 + plies))
        } else {
            runBlocking {
                engine.findBestMove(state.board, color, diff, Random(seed * 1000 + plies))
            }
        }
        // ⚠️ 诊断计数必须在**落子前**判断局面，且要用与引擎同一口径的定义：
        //    · trueEyeFill：落点是「真眼」（board.isOwnEye）却仍然落子 ⇒ 真正的低级错误，
        //      引擎的候选生成里**本该**排除它（collectCandidates 里有一句 isOwnEye 过滤）。
        //    · enclosed（四邻全自己但非真眼）：这是"假眼/自家地盘点"，补断、连接时是**合法**着法，
        //      所以只计数、不当错误 —— 我第一版把它当真错误是**错的**，先在这里改对。
        if (mv >= 0 && state.board.isOwnEye(mv, color)) trueEyeFills++
        if (mv >= 0) {
            val x = mv % size
            val y = mv / size
            val myCode = if (color == Stone.BLACK) 1 else 2
            var enclosed = true
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until size && ny in 0 until size) {
                    if (state.board.cells[ny * size + nx].toInt() != myCode) { enclosed = false; break }
                }
            }
            if (enclosed) enclosedFills++
            if (passes >= 2) postPassPlies++
        }
        val outcome = if (mv < 0) state.passMove(color) else state.play(mv, color)
        if (outcome !is MoveOutcome.Ok) {
            illegal = true            // 引擎给了非法手：显式暴露，不静默
            break
        }
        if (mv < 0) {
            passes++; consec++
            if (consec > maxConsec) maxConsec = consec
        } else {
            consec = 0
        }
        plies++
    }

    val res = state.result
    val winner = when (res?.winner) {
        Stone.BLACK -> if (aBlack) "A" else "B"
        Stone.WHITE -> if (aBlack) "B" else "A"
        else -> "none"
    }
    return buildString {
        append("{")
        append("\"size\":").append(size)
        append(",\"tierA\":\"").append(tierA).append("\"")
        append(",\"tierB\":\"").append(tierB).append("\"")
        append(",\"seed\":").append(seed)
        append(",\"aBlack\":").append(aBlack)
        append(",\"ended\":").append(state.isOver)
        append(",\"illegal\":").append(illegal)
        append(",\"plies\":").append(plies)
        append(",\"passes\":").append(passes)
        append(",\"maxConsecPass\":").append(maxConsec)
        append(",\"winner\":\"").append(winner).append("\"")
        append(",\"blackMargin\":").append(res?.score?.blackMargin ?: 0)
        append(",\"komi\":").append(state.komi)
        // 棋谱：为了能"看棋"做验收（只看胜负无法判断着法是否合理）。
        // ⚠️ bench 刻意不做 GTP 协议（见文件头注释），所以这里输出**原始索引与颜色**，
        //    由 Python 侧负责转 GTP —— 转换只在需要展示时发生，不进入评测主路径。
        append(",\"moveIdx\":[").append(state.moves.joinToString(",") { m ->
            (if (m.index == GameState.PASS_INDEX) -1 else m.index).toString()
        }).append("]")
        append(",\"moveColors\":\"").append(state.moves.joinToString("") { m ->
            if (m.color == Stone.BLACK) "B" else "W"
        }).append("\"")
        append(",\"handicap\":").append(state.handicap)
        append(",\"trueEyeFills\":").append(trueEyeFills)
        append(",\"enclosedFills\":").append(enclosedFills)
        append(",\"postPassPlies\":").append(postPassPlies)
        append("}")
    }
}
