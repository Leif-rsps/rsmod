package org.rsmod.api.config.editors

import org.rsmod.api.config.refs.invs
import org.rsmod.api.type.editors.inv.InvEditor
import org.rsmod.game.type.inv.InvScope
import org.rsmod.game.type.inv.InvStackType

internal object InvEdits : InvEditor() {
    init {
        edit(invs.inv) {
            scope = InvScope.Perm
            protect = false
            runWeight = true
        }

        edit(invs.worn) {
            scope = InvScope.Perm
            protect = false
            runWeight = true
        }

        edit(invs.bank) {
            scope = InvScope.Perm
            stack = InvStackType.Always
            protect = false
            placeholders = true
        }
    }
}
