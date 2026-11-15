package com.kinetix3d.evaluator

import kotlin.test.Test
import kotlin.test.assertEquals
import com.kinetix3d.model.ModuleInfo as CoreModelInfo

class ModuleInfoTest {
    @Test
    fun moduleNameIsCoreEvaluator() {
        assertEquals("core-evaluator", ModuleInfo.NAME)
    }

    @Test
    fun coreModelDependencyIsVisible() {
        assertEquals("core-model", CoreModelInfo.NAME)
    }
}
