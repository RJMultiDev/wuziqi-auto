import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.CanvasBasedWindow
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.fetch.RequestInit

enum class Cell { EMPTY, BLACK, WHITE }
enum class BotType(val label: String) { BUILTIN("内置算法"), LLM("LLM 提供商") }
enum class BuiltinAlgo(val label: String) { RANDOM("随机落子"), GREEDY("贪心评分") }

data class LlmConfig(
    val endpoint: String = "https://api.openai.com/v1/chat/completions",
    val apiKey: String = "",
    val model: String = "gpt-4.1-mini",
    val promptTemplate: String = "你是五子棋助手。仅输出\"(x,y)\"，坐标范围0..14。当前棋盘：{board}"
)

class GomokuState(val size: Int = 15) {
    val board = Array(size) { Array(size) { Cell.EMPTY } }
    var turn = Cell.BLACK
    var winner: Cell? = null

    fun reset() {
        for (y in 0 until size) for (x in 0 until size) board[y][x] = Cell.EMPTY
        turn = Cell.BLACK
        winner = null
    }

    fun place(x: Int, y: Int): Boolean {
        if (winner != null || x !in 0 until size || y !in 0 until size) return false
        if (board[y][x] != Cell.EMPTY) return false
        board[y][x] = turn
        if (isFive(x, y, turn)) winner = turn
        turn = if (turn == Cell.BLACK) Cell.WHITE else Cell.BLACK
        return true
    }

    private fun isFive(x: Int, y: Int, who: Cell): Boolean {
        val dirs = listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
        return dirs.any { (dx, dy) ->
            1 + countDir(x, y, dx, dy, who) + countDir(x, y, -dx, -dy, who) >= 5
        }
    }

    private fun countDir(x: Int, y: Int, dx: Int, dy: Int, who: Cell): Int {
        var nx = x + dx
        var ny = y + dy
        var c = 0
        while (nx in 0 until size && ny in 0 until size && board[ny][nx] == who) {
            c++; nx += dx; ny += dy
        }
        return c
    }

    fun legalMoves(): List<Pair<Int, Int>> = buildList {
        for (y in 0 until size) for (x in 0 until size) if (board[y][x] == Cell.EMPTY) add(x to y)
    }

    fun boardText(): String = buildString {
        for (y in 0 until size) {
            for (x in 0 until size) append(
                when (board[y][x]) { Cell.EMPTY -> "."; Cell.BLACK -> "X"; Cell.WHITE -> "O" }
            )
            append('\n')
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val scope = remember { MainScope() }
    val game = remember { GomokuState() }
    var botType by remember { mutableStateOf(BotType.BUILTIN) }
    var algo by remember { mutableStateOf(BuiltinAlgo.GREEDY) }
    var llm by remember { mutableStateOf(LlmConfig()) }
    var expanded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("你执黑先手") }
    var revision by remember { mutableStateOf(0) }

    fun askBot() {
        if (game.winner != null || game.turn != Cell.WHITE) return
        scope.launch {
            val move = when (botType) {
                BotType.BUILTIN -> builtinMove(game, algo)
                BotType.LLM -> llmMove(game, llm)
            }
            if (move != null && game.place(move.first, move.second)) {
                status = game.winner?.let { "胜者: ${if (it == Cell.BLACK) "黑" else "白"}" } ?: "轮到你下"
                revision++
            } else {
                status = "机器落子失败，请检查配置"
            }
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("在线五子棋（Compose Multiplatform Web/Wasm）", fontWeight = FontWeight.Bold)
                Text(status)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                        OutlinedTextField(
                            value = botType.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("机器类型") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor().width(220.dp)
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            BotType.entries.forEach {
                                DropdownMenuItem(text = { Text(it.label) }, onClick = { botType = it; expanded = false })
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (botType == BotType.BUILTIN) {
                        Button(onClick = { algo = if (algo == BuiltinAlgo.RANDOM) BuiltinAlgo.GREEDY else BuiltinAlgo.RANDOM }) {
                            Text("算法: ${algo.label}")
                        }
                    }
                }
                if (botType == BotType.LLM) {
                    OutlinedTextField(llm.endpoint, { llm = llm.copy(endpoint = it) }, label = { Text("Completions 端点") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(llm.apiKey, { llm = llm.copy(apiKey = it) }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(llm.model, { llm = llm.copy(model = it) }, label = { Text("模型") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(llm.promptTemplate, { llm = llm.copy(promptTemplate = it) }, label = { Text("提示词模板") }, modifier = Modifier.fillMaxWidth())
                }
                Board(game, revision) { x, y ->
                    if (game.turn == Cell.BLACK && game.place(x, y)) {
                        status = game.winner?.let { "胜者: 黑" } ?: "机器思考中..."
                        revision++
                        askBot()
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { game.reset(); revision++; status = "你执黑先手" }) { Text("重开") }
                    Button(onClick = { askBot() }) { Text("让机器落子") }
                }
            }
        }
    }
}

@Composable
fun Board(game: GomokuState, rev: Int, onTap: (Int, Int) -> Unit) {
    val size = 600.dp
    val cell = 40f
    Column(Modifier.size(size).border(1.dp, Color.Gray).background(Color(0xFFDEB887))) {
        Canvas(Modifier.size(size).pointerInput(rev) {
            detectTapGestures { offset ->
                onTap((offset.x / cell).toInt().coerceIn(0, 14), (offset.y / cell).toInt().coerceIn(0, 14))
            }
        }) {
            for (i in 0..14) {
                drawLine(Color.Black, Offset((i + 0.5f) * cell, 0.5f * cell), Offset((i + 0.5f) * cell, 14.5f * cell), 1f)
                drawLine(Color.Black, Offset(0.5f * cell, (i + 0.5f) * cell), Offset(14.5f * cell, (i + 0.5f) * cell), 1f)
            }
            for (y in 0 until 15) for (x in 0 until 15) {
                when (game.board[y][x]) {
                    Cell.BLACK -> drawCircle(Color.Black, 14f, Offset((x + 0.5f) * cell, (y + 0.5f) * cell))
                    Cell.WHITE -> {
                        drawCircle(Color.White, 14f, Offset((x + 0.5f) * cell, (y + 0.5f) * cell))
                        drawCircle(Color.Black, 14f, Offset((x + 0.5f) * cell, (y + 0.5f) * cell), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                    }
                    else -> Unit
                }
            }
        }
    }
}

fun builtinMove(game: GomokuState, algo: BuiltinAlgo): Pair<Int, Int>? {
    val legal = game.legalMoves()
    if (legal.isEmpty()) return null
    if (algo == BuiltinAlgo.RANDOM) return legal.random()
    return legal.maxByOrNull { scoreMove(game, it.first, it.second, Cell.WHITE) + scoreMove(game, it.first, it.second, Cell.BLACK) }
}

fun scoreMove(game: GomokuState, x: Int, y: Int, who: Cell): Int {
    val dirs = listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
    return dirs.sumOf { (dx, dy) ->
        fun count(step: Int): Int {
            var nx = x + dx * step
            var ny = y + dy * step
            var c = 0
            while (nx in 0 until game.size && ny in 0 until game.size && game.board[ny][nx] == who) {
                c++; nx += dx * step; ny += dy * step
            }
            return c
        }
        when (val line = count(1) + count(-1)) {
            in 4..10 -> 100000
            3 -> 5000
            2 -> 300
            else -> 30
        }
    }
}

suspend fun llmMove(game: GomokuState, config: LlmConfig): Pair<Int, Int>? {
    val prompt = config.promptTemplate.replace("{board}", game.boardText())
    val esc = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    val body = """{"model":"${config.model}","messages":[{"role":"user","content":"$esc"}],"temperature":0.2}"""
    val res = window.fetch(config.endpoint, RequestInit(
        method = "POST",
        headers = js("({ 'Content-Type': 'application/json', 'Authorization': 'Bearer ${config.apiKey}' })"),
        body = body
    )).await()
    val text = res.text().await()
    val match = Regex("""\((\d+)\s*,\s*(\d+)\)""").find(text) ?: return null
    return match.groupValues[1].toInt() to match.groupValues[2].toInt()
}

fun main() = CanvasBasedWindow("五子棋") { App() }
