package edu.pitt.isl.mobileclaw.notiftest.skills.appfunctions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell command's output, as captured on the Pixel 7 (Android 16),
 * rendered for the model. The captures are verbatim, indentation included,
 * because the grammar is undocumented and the only spec is what the platform prints.
 */
class AppFunctionResultTest {

    @Test fun `only platform success or structured return confirms execution`() {
        assertTrue(AppFunctionResult.executionConfirmed("App function executed successfully.\nFunction return: {}"))
        assertTrue(AppFunctionResult.executionConfirmed("""{"androidAppfunctionsReturnValue": []}"""))
        assertTrue(AppFunctionResult.executionConfirmed("""{"androidAppfunctionsReturnValue": null}"""))
        assertFalse(AppFunctionResult.executionConfirmed("Error executing app function: 1003 - no such function"))
        assertFalse(AppFunctionResult.executionConfirmed("done"))
        assertFalse(AppFunctionResult.executionConfirmed("""{"unrelated": "success"}"""))
        assertFalse(AppFunctionResult.executionConfirmed("{bad"))
    }

    private val fn = "edu.pitt.isl.mobileclaw.taskboard.TaskBoardFunctions#setThemeColor"

    @Test
    fun `a string return is one line`() {
        val raw = """
            App function executed successfully.
            Function return:
            {
              namespace: "",
              id: "",
              score: 0,
              schemaType: "",
              creationTimestampMillis: 1788567766229,
              timeToLiveMillis: 0,
              properties: {
                "androidAppfunctionsReturnValue": ["#6A1B9A"]
              }
            }
        """.trimIndent()
        assertEquals("setThemeColor returned: #6A1B9A", AppFunctionResult.render(fn, raw))
    }

    @Test
    fun `an object return becomes key-value lines without the document plumbing`() {
        val raw = """
            App function executed successfully.
            Function return:
            {
              namespace: "",
              id: "",
              score: 0,
              schemaType: "",
              creationTimestampMillis: 1,
              timeToLiveMillis: 0,
              properties: {
                "androidAppfunctionsReturnValue": [
                  {
                    namespace: "",
                    id: "",
                    score: 0,
                    schemaType: "edu.pitt.isl.mobileclaw.taskboard.TaskInfo",
                    creationTimestampMillis: 2,
                    timeToLiveMillis: 0,
                    properties: {
                      "done": [false],
                      "id": [3],
                      "note": ["the salmon one"],
                      "title": ["buy cat food"]
                    }
                  }
                ]
              }
            }
        """.trimIndent()
        val out = AppFunctionResult.render("x.TaskBoardFunctions#addTask", raw)
        assertEquals("addTask returned: \ndone: false\nid: 3\nnote: the salmon one\ntitle: buy cat food", out)
        assertFalse(out.contains("schemaType"))
        assertFalse(out.contains("namespace"))
    }

    @Test
    fun `a list of objects is numbered, the ChatApp capture included`() {
        val raw = """
            App function executed successfully.
            Function return:
            {
              namespace: "",
              id: "",
              score: 0,
              schemaType: "",
              creationTimestampMillis: 1788567766229,
              timeToLiveMillis: 0,
              properties: {
                "androidAppfunctionsReturnValue": [
                  {
                    namespace: "",
                    id: "",
                    score: 0,
                    schemaType: "com.example.chatapp.appfunctions.ContactSearchResult",
                    creationTimestampMillis: 1788567766225,
                    timeToLiveMillis: 0,
                    properties: {
                      "contactDisplayName": ["Alice Smith"],
                      "contactType": ["INDIVIDUAL"],
                      "endpointDisplayName": ["alice@example.com"],
                      "endpointValue": ["1"]
                    }
                  },

                  {
                    namespace: "",
                    id: "",
                    score: 0,
                    schemaType: "com.example.chatapp.appfunctions.ContactSearchResult",
                    creationTimestampMillis: 1788567766225,
                    timeToLiveMillis: 0,
                    properties: {
                      "contactDisplayName": ["Bob Johnson"],
                      "contactType": ["INDIVIDUAL"],
                      "endpointDisplayName": ["bob@example.com"],
                      "endpointValue": ["2"]
                    }
                  }
                ]
              }
            }
        """.trimIndent()
        val out = AppFunctionResult.render("com.example.chatapp.appfunctions.BaseChatAppFunctionService#searchContacts", raw)
        assertTrue(out, out.startsWith("searchContacts returned: \n1. contactDisplayName: Alice Smith\n   contactType: INDIVIDUAL"))
        assertTrue(out, out.contains("\n2. contactDisplayName: Bob Johnson"))
        assertTrue(out, out.contains("endpointValue: 2"))
    }

    @Test
    fun `a list of strings inside an object is joined`() {
        val raw = """
            App function executed successfully.
            Function return:
            { properties: { "androidAppfunctionsReturnValue": [ { schemaType: "x.BoardState", properties: { "themeColor": ["#6A1B9A"], "openTasks": [2], "doneTasks": [0], "taskTitles": ["buy cat food", "call mum"] } } ] } }
        """.trimIndent()
        val out = AppFunctionResult.render("x.TaskBoardFunctions#getBoard", raw)
        assertEquals("getBoard returned: \nthemeColor: #6A1B9A\nopenTasks: 2\ndoneTasks: 0\ntaskTitles: buy cat food, call mum", out)
    }

    @Test
    fun `an empty list and a missing return value are said, not blank`() {
        val raw = "App function executed successfully.\nFunction return:\n{ properties: { \"androidAppfunctionsReturnValue\": [] } }"
        assertEquals("listTasks returned: (empty list)", AppFunctionResult.render("x.F#listTasks", raw))
        assertEquals("doIt succeeded with no return value.", AppFunctionResult.render("x.F#doIt", "App function executed successfully."))
    }

    @Test
    fun `the platform's two error formats become one reason`() {
        assertEquals(
            "ping failed: The app function is disabled",
            AppFunctionResult.render("x.F#ping", "Error executing app function: 1002 - android.app.appfunctions.AppFunctionException: The app function is disabled")
        )
        assertEquals(
            "ping failed: The app function is disabled",
            AppFunctionResult.render("x.F#ping", "Error executing app function: android.app.appfunctions.AppFunctionException: The app function is disabled (code 1002). See logcat for more details.")
        )
    }

    @Test
    fun `a bare error code is translated, because the callee's message does not survive the platform`() {
        assertEquals(
            "completeTask failed: the item the call named was not found",
            AppFunctionResult.render("x.F#completeTask", "Error executing app function: 1500 - android.app.appfunctions.AppFunctionException")
        )
        assertEquals(
            "setThemeColor failed: an argument was invalid (the app rejected the parameters)",
            AppFunctionResult.render("x.F#setThemeColor", "Error executing app function: 1001 - android.app.appfunctions.AppFunctionException")
        )
        assertEquals("f failed: error code 4242", AppFunctionResult.render("x.F#f", "Error executing app function: 4242 - android.app.appfunctions.AppFunctionException"))
    }

    @Test
    fun `quotes inside a returned string are not escaped by the platform and still parse`() {
        // Verbatim from the Pixel 7: the callee returned "sea" is not a colour …
        val raw = "App function executed successfully.\nFunction return:\n{\n  properties: {\n    \"androidAppfunctionsReturnValue\": [\"\"sea\" is not a colour I know; use #RRGGBB\"]\n  }\n}"
        assertEquals("setThemeColor returned: \"sea\" is not a colour I know; use #RRGGBB", AppFunctionResult.render(fn, raw))
    }

    @Test
    fun `plain JSON without a success line renders getBoard`() {
        val raw = """
            {
              "androidAppfunctionsReturnValue": [
                {
                  "themeColor": [
                    "#1565C0"
                  ],
                  "taskTitles": [],
                  "openTasks": [
                    0
                  ],
                  "doneTasks": [
                    0
                  ]
                }
              ]
            }
        """.trimIndent()
        assertEquals(
            "getBoard returned: \nthemeColor: #1565C0\ntaskTitles: (none)\nopenTasks: 0\ndoneTasks: 0",
            AppFunctionResult.render("edu.pitt.isl.mobileclaw.taskboard.TaskBoardFunctions#getBoard", raw)
        )
    }

    @Test
    fun `in the JSON shape id is the function's own field and survives`() {
        val raw = """{"androidAppfunctionsReturnValue": [{"done": [false], "id": [3], "note": ["salmon flavour"], "title": ["buy cat food"]}]}"""
        assertEquals(
            "addTask returned: \ndone: false\nid: 3\nnote: salmon flavour\ntitle: buy cat food",
            AppFunctionResult.render("x.TaskBoardFunctions#addTask", raw)
        )
        assertEquals("setThemeColor returned: theme colour is now #6A1B9A",
            AppFunctionResult.render("x.F#setThemeColor", """{"androidAppfunctionsReturnValue": ["theme colour is now #6A1B9A"]}"""))
    }

    @Test
    fun `an unfamiliar shape comes back verbatim rather than lost`() {
        val odd = "App function executed successfully.\nFunction return:\n<<binary>>"
        assertEquals(odd, AppFunctionResult.render("x.F#odd", odd))
        assertEquals("something else entirely", AppFunctionResult.render("x.F#odd", "something else entirely"))
    }
}
