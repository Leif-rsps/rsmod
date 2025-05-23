package org.rsmod.api.combat.formulas.attributes.collector

import java.util.EnumSet
import org.rsmod.api.combat.formulas.attributes.CombatNpcAttributes
import org.rsmod.api.config.constants
import org.rsmod.api.config.refs.categories
import org.rsmod.api.config.refs.npcs
import org.rsmod.api.config.refs.params
import org.rsmod.game.type.npc.UnpackedNpcType

public class CombatNpcAttributeCollector {
    public fun collect(
        type: UnpackedNpcType,
        currHp: Int,
        maxHp: Int,
        slayerTask: Boolean,
    ): EnumSet<CombatNpcAttributes> {
        val attributes = EnumSet.noneOf(CombatNpcAttributes::class.java)

        if (currHp < maxHp / 4) {
            attributes += CombatNpcAttributes.QuarterHealth
        }

        if (slayerTask) {
            attributes += CombatNpcAttributes.SlayerTask
        }

        // TODO(combat): "In wilderness" area check.

        val sizeAttribute =
            when (val size = type.size) {
                2 -> CombatNpcAttributes.Size2
                3 -> CombatNpcAttributes.Size3
                4 -> CombatNpcAttributes.Size4
                else -> {
                    if (size >= 5) {
                        CombatNpcAttributes.Size5OrMore
                    } else {
                        null
                    }
                }
            }

        if (sizeAttribute != null) {
            attributes += sizeAttribute
        }

        val elementWeaknessAttribute =
            when (type.paramOrNull(params.elemental_weakness_type)) {
                constants.elemental_weakness_wind -> CombatNpcAttributes.WindWeakness
                constants.elemental_weakness_water -> CombatNpcAttributes.WaterWeakness
                constants.elemental_weakness_earth -> CombatNpcAttributes.EarthWeakness
                constants.elemental_weakness_fire -> CombatNpcAttributes.FireWeakness
                else -> null
            }

        if (elementWeaknessAttribute != null) {
            attributes += elementWeaknessAttribute
        }

        if (type.param(params.revenant) != 0) {
            attributes += CombatNpcAttributes.Revenant
        }

        if (type.param(params.undead) != 0) {
            attributes += CombatNpcAttributes.Undead
        }

        if (type.param(params.demon) != 0) {
            attributes += CombatNpcAttributes.Demon
        }

        if (type.param(params.demonbane_resistant) != 0) {
            attributes += CombatNpcAttributes.DemonbaneResistance
        }

        if (type.param(params.draconic) != 0) {
            attributes += CombatNpcAttributes.Draconic
        }

        if (type.param(params.kalphite) != 0) {
            attributes += CombatNpcAttributes.Kalphite
        }

        if (type.param(params.golem) != 0) {
            attributes += CombatNpcAttributes.Golem
        }

        if (type.param(params.leafy) != 0) {
            attributes += CombatNpcAttributes.Leafy
        }

        if (type.param(params.rat) != 0) {
            attributes += CombatNpcAttributes.Rat
        }

        if (type.param(params.shade) != 0) {
            attributes += CombatNpcAttributes.Shade
        }

        if (type.isCategoryType(categories.vampyres)) {
            attributes += CombatNpcAttributes.Vampyre
        }

        if (type.param(params.tormented_demon) != 0 && !type.param(params.td_shield_active)) {
            attributes += CombatNpcAttributes.TormentedDemonUnshielded
        }

        if (type.isType(npcs.corp_beast)) {
            attributes += CombatNpcAttributes.CorporealBeast
        }

        if (type.param(params.xerician) != 0) {
            attributes += CombatNpcAttributes.Xerician
        }

        if (type.param(params.amascutnpc) != 0) {
            attributes += CombatNpcAttributes.Amascut
        }

        return attributes
    }
}
