package androidx.media3.extractor.metadata.dvbsi

import androidx.media3.common.Metadata
import androidx.media3.common.util.ParsableBitArray

import androidx.media3.extractor.metadata.MetadataInputBuffer
import androidx.media3.extractor.metadata.SimpleMetadataDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class AppInfoTableDecoder : SimpleMetadataDecoder() {
    // Buffer validated by SimpleMetadataDecoder.decode
    override fun decode(inputBuffer: MetadataInputBuffer, buffer: ByteBuffer): Metadata? {
        val tableId = buffer.get().toInt()
        return if (tableId == APPLICATION_INFORMATION_TABLE_ID)
            parseAit(ParsableBitArray(buffer.array(), buffer.limit()))
        else
            null
    }

    companion object {
        
        private const val DESCRIPTOR_TRANSPORT_PROTOCOL = 0x02

        private const val DESCRIPTOR_SIMPLE_APPLICATION_LOCATION = 0x15

        private const val TRANSPORT_PROTOCOL_HTTP = 3

        const val APPLICATION_INFORMATION_TABLE_ID: Int = 0x74

        private fun parseAit(sectionData: ParsableBitArray): Metadata? {
            // tableId, section_syntax_indication, reserved_future_use, reserved
            sectionData.skipBits(12)
            val sectionLength = sectionData.readBits(12)
            val endOfSection =
                sectionData.bytePosition + sectionLength - 4 

            // test_application_flag, application_type, reserved, version_number, current_next_indicator,
            // section_number, last_section_number, reserved_future_use
            sectionData.skipBits(44)

            val commonDescriptorsLength = sectionData.readBits(12)

            // Since we currently only keep URL and control code, which are unique per application,
            // there is no useful information in common descriptor.
            sectionData.skipBytes(commonDescriptorsLength)

            // reserved_future_use, application_loop_length
            sectionData.skipBits(16)

            val appInfoTables = ArrayList<AppInfoTable>()
            while (sectionData.bytePosition < endOfSection) {
                var urlBase: String? = null
                var urlExtension: String? = null

                // application_identifier
                sectionData.skipBits(48)

                val controlCode = sectionData.readBits(8)

                // reserved_future_use
                sectionData.skipBits(4)

                val applicationDescriptorsLoopLength = sectionData.readBits(12)
                val positionOfNextApplication =
                    sectionData.bytePosition + applicationDescriptorsLoopLength
                while (sectionData.bytePosition < positionOfNextApplication) {
                    val descriptorTag = sectionData.readBits(8)
                    val descriptorLength = sectionData.readBits(8)
                    val positionOfNextDescriptor = sectionData.bytePosition + descriptorLength

                    if (descriptorTag == DESCRIPTOR_TRANSPORT_PROTOCOL) {
                        // See section 5.3.6.
                        val protocolId = sectionData.readBits(16)
                        // label
                        sectionData.skipBits(8)

                        if (protocolId == TRANSPORT_PROTOCOL_HTTP) {
                            // See section 5.3.6.2.
                            while (sectionData.bytePosition < positionOfNextDescriptor) {
                                val urlBaseLength = sectionData.readBits(8)
                                urlBase = sectionData.readBytesAsString(
                                    urlBaseLength,
                                    StandardCharsets.US_ASCII
                                )

                                val extensionCount = sectionData.readBits(8)
                                for (urlExtensionIndex in 0..<extensionCount) {
                                    val urlExtensionLength = sectionData.readBits(8)
                                    sectionData.skipBytes(urlExtensionLength)
                                }
                            }
                        }
                    } else if (descriptorTag == DESCRIPTOR_SIMPLE_APPLICATION_LOCATION) {
                        // See section 5.3.7.
                        urlExtension = sectionData.readBytesAsString(
                            descriptorLength,
                            StandardCharsets.US_ASCII
                        )
                    }

                    sectionData.position=(positionOfNextDescriptor * 8)
                }

                sectionData.position=(positionOfNextApplication * 8)

                if (urlBase != null && urlExtension != null) {
                    appInfoTables.add(AppInfoTable(controlCode, urlBase + urlExtension))
                }
            }

            return if (appInfoTables.isEmpty()) null else Metadata(appInfoTables)
        }
    }
}
