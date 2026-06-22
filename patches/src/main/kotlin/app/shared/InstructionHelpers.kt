package app.shared

import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Shared reference-decoding helpers for the structural instruction scans used by
 * several patches. The patcher's fingerprint filters / `Match` accessors cover the
 * common case, but the manual backward/forward register-window scans (R8-resilient
 * dataflow gating) still need to decode a single instruction's reference by hand —
 * these are that decode, kept here once instead of copied into each patch.
 */

internal fun Instruction.methodReferenceOrNull(): MethodReference? =
    (this as? ReferenceInstruction)?.reference as? MethodReference

internal fun Instruction.fieldReferenceOrNull(): FieldReference? =
    (this as? ReferenceInstruction)?.reference as? FieldReference

internal fun Instruction.stringReferenceOrNull(): String? =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
