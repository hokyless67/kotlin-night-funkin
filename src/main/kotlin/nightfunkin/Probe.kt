package nightfunkin

import nightfunkin.play.Character

/** Temporary diagnostic: pixel death render state. */
fun main() {
    val c = Character("bf-pixel")
    val (cx, cy) = c.idleFrameCenter()
    println("idleFrameCenter=($cx, $cy)")
    c.play("firstDeath", forceRestart = true)
    c.update(1.0 / 60.0 * 5)
    println(c.debugRender())
    val bf = Character("bf")
    val (bx, by) = bf.idleFrameCenter()
    println("bf idleFrameCenter=($bx, $by)")
}
