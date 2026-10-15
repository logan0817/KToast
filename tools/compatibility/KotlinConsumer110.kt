package compatibility

import com.logan.ktoast.KToastScopeOptions

fun main() {
    val options = KToastScopeOptions(avoidIme = true, followHostTheme = true)
    val copy = options.copy(avoidSystemBars = false)
    val (bars, ime, timeout, theme, diagnostics) = copy
    check(!bars && ime && timeout && theme && diagnostics == null)
    check(copy == KToastScopeOptions(false, true, true, true))
    println("PASS: Kotlin consumer compiled against 1.1.0")
}
