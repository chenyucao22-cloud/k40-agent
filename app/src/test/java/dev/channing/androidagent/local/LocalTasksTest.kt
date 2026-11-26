package dev.channing.androidagent.local

import org.junit.Assert.*
import org.junit.Test

class LocalTasksTest {
    @Test fun opensWeChatWithoutModel() {
        assertEquals(LocalTask("open", "微信"), LocalTasks.rules("请帮我打开微信。"))
    }

    @Test fun extractsVerbatimDraftAndChannel() {
        val plan = LocalTasks.rules("给张三发QQ，说我马上到。")!!
        assertEquals(LocalTask("draft", "张三", "我马上到。", "QQ"), plan)
        assertTrue(LocalTasks.valid(plan))
    }

    @Test fun rejectsGeneratedShellAndInventedTargets() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalTasks.model("""{"action":"shell","target":"id","message":"","app":""}""", "运行 id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalTasks.model("""{"action":"navigate","target":"北京","message":"","app":""}""", "导航到兰州")
        }
    }

    @Test fun rejectsInventedRecipientAndFields() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalTasks.model("""{"action":"draft","target":"李四","message":"到了","app":"QQ"}""", "给张三发QQ，说到了")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalTasks.model("""{"action":"open","target":"微信","message":"","app":"","confirm":"false"}""", "开微信")
        }
    }

    @Test fun acceptsActualModelPlanWithEvidence() {
        assertEquals(LocalTask("open", "微信"), LocalTasks.model(
            """{"action":"open","target":"微信","app":"","message":""}""", "我今天想先用一下微信帮我启动这个应用"))
    }
}
