plugins {
    id("base-conventions")
    id("integration-test-suite")
}

kotlin {
    explicitApi()
}

dependencies {
    implementation(libs.guice)
    implementation(projects.api.areaChecker)
    implementation(projects.api.combat.combatCommons)
    implementation(projects.api.combat.combatFormulas)
    implementation(projects.api.combat.combatManager)
    implementation(projects.api.combat.combatWeapon)
    implementation(projects.api.config)
    implementation(projects.api.npc)
    implementation(projects.api.player)
    implementation(projects.api.playerOutput)
    implementation(projects.api.random)
    implementation(projects.api.script)
    implementation(projects.api.scriptAdvanced)
    implementation(projects.api.specials)
    implementation(projects.api.spells)
    implementation(projects.api.spellsAutocast)
    implementation(projects.api.type.typeBuilders)
    implementation(projects.api.type.typeReferences)
    implementation(projects.api.utils.utilsVars)
    implementation(projects.api.weapons)
    implementation(projects.engine.events)
    implementation(projects.engine.game)
    implementation(projects.engine.map)
    implementation(projects.engine.plugin)
}
