package com.kinetix3d.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun moduleNameIsCoreModel() {
        assertEquals("core-model", ModuleInfo.NAME)
    }
}
