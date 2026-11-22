package com.kinetix3d.render

import kotlin.test.Test
import kotlin.test.assertEquals

class DemoGraphTest {
    @Test
    fun compositeGraphCarriesTheBlendMode() {
        val graph = demoCompositeGraph(2f)
        val composite = graph.nodes.values.first { it.schemaId == "kinetix.composite" }
        assertEquals(2f, composite.params.getValue("blendMode").value)
    }
}
