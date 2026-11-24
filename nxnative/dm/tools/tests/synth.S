// Synthetic module for exercising the rewriter's jump-table recognizers (test only, not committed).
// Function A: the offset-table form the rewriter has always taken (regression: identical output before and after the port).
// Function B: the ordinary-function tail call the new proof accepts (Warm Snow / Ori and the Blind Forest form):
//   the old rewriter stops at `jump table` here, the new one classifies it as a tail call.
.arch armv8-a
.text

    // the module header the loader expects: MOD0's offset lives at image offset 4 (patched in by the wrapper)
    .word   0
mod0_ptr:
    .word   0           // replaced with MOD0's offset by synth.py

    .align  2
// ---- A: offset-table dispatch ----
funcA:
    adrp    x2, tbl
    add     x2, x2, :lo12:tbl
    cmp     w0, #1
    b.hi    1f
    ldrsw   x1, [x2, x0, lsl #2]
    add     x18, x1, x2
    br      x18
1:  mov     w0, #0
    ret

    .align  2
// ---- B: an ordinary function ending in an indirect tail call ----
funcB:
    sub     sp, sp, #0x20
    stp     x29, x30, [sp, #0x10]
    mov     w19, w0
    ldp     x29, x30, [sp, #0x10]
    ldr     x18, [x0]
    add     sp, sp, #0x20
    br      x18
    add     sp, sp, #0x20
    ret

    .align  2
tbl:
    .word   fa0 - tbl
    .word   fa1 - tbl
fa0:
    mov     w0, #1
    ret
fa1:
    mov     w0, #2
    ret
