package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.BehaviorHintsDto
import com.nuvio.tv.data.remote.dto.StreamPreparationHintDto
import org.junit.Assert.*
import org.junit.Test

class StreamPreparationMapperTest {
    @Test fun `legacy hint remains absent and advertised preparation survives mapping`() {
        assertNull(BehaviorHintsDto().toDomain().streamPreparation)
        assertEquals("https://example.test/prep", BehaviorHintsDto(
            streamPreparation = StreamPreparationHintDto("https://example.test/prep")
        ).toDomain().streamPreparation?.url)
    }
    @Test fun `malformed advertised hint is preserved to fail closed`() {
        assertEquals("invalid:", BehaviorHintsDto(streamPreparation = StreamPreparationHintDto())
            .toDomain().streamPreparation?.url)
    }
}
