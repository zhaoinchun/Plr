package androidx.media3.container

import androidx.media3.common.util.ParsableByteArray

abstract class Mp4Box // private to only allow sub-classing from within this file.
private constructor(val type: Int) {
    override fun toString(): String {
        return getBoxTypeString(type)
    }

    class LeafBox(type: Int, val data: ParsableByteArray?) : Mp4Box(type)

    class ContainerBox(type: Int, val endPosition: Long) : Mp4Box(type) {
        val leafChildren: MutableList<LeafBox>
        val containerChildren: MutableList<ContainerBox>

        init {
            leafChildren = ArrayList<LeafBox>()
            containerChildren = ArrayList<ContainerBox>()
        }

        fun add(box: LeafBox?) {
            leafChildren.add(box!!)
        }

        fun add(box: ContainerBox?) {
            containerChildren.add(box!!)
        }

        fun getLeafBoxOfType(type: Int): LeafBox? {
            val childrenSize = leafChildren.size
            for (i in 0..<childrenSize) {
                val box = leafChildren[i]
                if (box.type == type) {
                    return box
                }
            }
            return null
        }

        fun getContainerBoxOfType(type: Int): ContainerBox? {
            val childrenSize = containerChildren.size
            for (i in 0..<childrenSize) {
                val box = containerChildren[i]
                if (box.type == type) {
                    return box
                }
            }
            return null
        }

        override fun toString(): String {
            return (getBoxTypeString(type)
                    + " leaves: "
                    + leafChildren.toTypedArray().contentToString() + " containers: "
                    + containerChildren.toTypedArray().contentToString())
        }
    }

    companion object {
        const val HEADER_SIZE: Int = 8
        const val FULL_HEADER_SIZE: Int = 12
        const val LONG_HEADER_SIZE: Int = 16
        const val DEFINES_LARGE_SIZE: Int = 1
        const val EXTENDS_TO_END_SIZE: Int = 0

        const val TYPE_ftyp: Int = 0x66747970

        const val TYPE_apv1: Int = 0x61707631

        const val TYPE_apvC: Int = 0x61707643

        const val TYPE_avc1: Int = 0x61766331

        const val TYPE_avc3: Int = 0x61766333

        const val TYPE_avcC: Int = 0x61766343

        const val TYPE_hvc1: Int = 0x68766331

        const val TYPE_hev1: Int = 0x68657631

        const val TYPE_hvcC: Int = 0x68766343

        const val TYPE_lhvC: Int = 0x6C687643

        const val TYPE_vvc1: Int = 0x76766331

        const val TYPE_vvi1: Int = 0x76766931

        const val TYPE_vvcC: Int = 0x76766343

        const val TYPE_vexu: Int = 0x76657875

        const val TYPE_eyes: Int = 0x65796573

        const val TYPE_stri: Int = 0x73747269

        const val TYPE_vp08: Int = 0x76703038

        const val TYPE_vp09: Int = 0x76703039

        const val TYPE_vpcC: Int = 0x76706343

        const val TYPE_av01: Int = 0x61763031

        const val TYPE_av1C: Int = 0x61763143

        const val TYPE_colr: Int = 0x636f6c72

        const val TYPE_dvav: Int = 0x64766176

        const val TYPE_dva1: Int = 0x64766131

        const val TYPE_dvhe: Int = 0x64766865

        const val TYPE_dvh1: Int = 0x64766831

        const val TYPE_dvcC: Int = 0x64766343

        const val TYPE_dav1: Int = 0x64617631

        const val TYPE_dvvC: Int = 0x64767643

        const val TYPE_dvwC: Int = 0x64767743

        const val TYPE_s263: Int = 0x73323633

        const val TYPE_H263: Int = 0x48323633

        const val TYPE_h263: Int = 0x68323633

        const val TYPE_d263: Int = 0x64323633

        const val TYPE_mdat: Int = 0x6d646174

        const val TYPE_mp4a: Int = 0x6d703461

        const val TYPE__mp2: Int = 0x2e6d7032

        const val TYPE__mp3: Int = 0x2e6d7033

        const val TYPE_mha1: Int = 0x6d686131

        const val TYPE_mhm1: Int = 0x6d686d31

        const val TYPE_mhaC: Int = 0x6d686143

        const val TYPE_mhaP: Int = 0x6d686150

        const val TYPE_wave: Int = 0x77617665

        const val TYPE_lpcm: Int = 0x6c70636d

        const val TYPE_sowt: Int = 0x736f7774

        const val TYPE_ac_3: Int = 0x61632d33

        const val TYPE_dac3: Int = 0x64616333

        const val TYPE_ec_3: Int = 0x65632d33

        const val TYPE_dec3: Int = 0x64656333

        const val TYPE_ac_4: Int = 0x61632d34

        const val TYPE_dac4: Int = 0x64616334

        const val TYPE_mlpa: Int = 0x6d6c7061

        const val TYPE_dmlp: Int = 0x646d6c70

        const val TYPE_dtsc: Int = 0x64747363

        const val TYPE_dtsh: Int = 0x64747368

        const val TYPE_dtsl: Int = 0x6474736c

        const val TYPE_dtse: Int = 0x64747365

        const val TYPE_dtsx: Int = 0x64747378

        const val TYPE_ddts: Int = 0x64647473

        const val TYPE_udts: Int = 0x75647473

        const val TYPE_tfdt: Int = 0x74666474

        const val TYPE_tfhd: Int = 0x74666864

        const val TYPE_tfra: Int = 0x74667261

        const val TYPE_mfro: Int = 0x6d66726f

        const val TYPE_mfra: Int = 0x6d667261

        const val TYPE_trex: Int = 0x74726578

        const val TYPE_trun: Int = 0x7472756e

        const val TYPE_sidx: Int = 0x73696478

        const val TYPE_moov: Int = 0x6d6f6f76

        const val TYPE_mpvd: Int = 0x6d707664

        const val TYPE_mvhd: Int = 0x6d766864

        const val TYPE_trak: Int = 0x7472616b

        const val TYPE_mdia: Int = 0x6d646961

        const val TYPE_minf: Int = 0x6d696e66

        const val TYPE_stbl: Int = 0x7374626c

        const val TYPE_esds: Int = 0x65736473

        const val TYPE_btrt: Int = 0x62747274

        const val TYPE_moof: Int = 0x6d6f6f66

        const val TYPE_traf: Int = 0x74726166

        const val TYPE_mvex: Int = 0x6d766578

        const val TYPE_mehd: Int = 0x6d656864

        const val TYPE_tkhd: Int = 0x746b6864

        const val TYPE_edts: Int = 0x65647473

        const val TYPE_elst: Int = 0x656c7374

        const val TYPE_mdhd: Int = 0x6d646864

        const val TYPE_hdlr: Int = 0x68646c72

        const val TYPE_stsd: Int = 0x73747364

        const val TYPE_pssh: Int = 0x70737368

        const val TYPE_sinf: Int = 0x73696e66

        const val TYPE_schm: Int = 0x7363686d

        const val TYPE_schi: Int = 0x73636869

        const val TYPE_tenc: Int = 0x74656e63

        const val TYPE_encv: Int = 0x656e6376

        const val TYPE_enca: Int = 0x656e6361

        const val TYPE_frma: Int = 0x66726d61

        const val TYPE_saiz: Int = 0x7361697a

        const val TYPE_saio: Int = 0x7361696f

        const val TYPE_sbgp: Int = 0x73626770

        const val TYPE_sgpd: Int = 0x73677064

        const val TYPE_uuid: Int = 0x75756964

        const val TYPE_senc: Int = 0x73656e63

        const val TYPE_pasp: Int = 0x70617370

        const val TYPE_TTML: Int = 0x54544d4c

        const val TYPE_m1v_: Int = 0x6d317620

        const val TYPE_mp4v: Int = 0x6d703476

        const val TYPE_mp4s: Int = 0x6D703473

        const val TYPE_stts: Int = 0x73747473

        const val TYPE_stss: Int = 0x73747373

        const val TYPE_ctts: Int = 0x63747473

        const val TYPE_stsc: Int = 0x73747363

        const val TYPE_stsz: Int = 0x7374737a

        const val TYPE_stz2: Int = 0x73747a32

        const val TYPE_stco: Int = 0x7374636f

        const val TYPE_co64: Int = 0x636f3634

        const val TYPE_tx3g: Int = 0x74783367

        const val TYPE_wvtt: Int = 0x77767474

        const val TYPE_stpp: Int = 0x73747070

        const val TYPE_c608: Int = 0x63363038

        const val TYPE_samr: Int = 0x73616d72

        const val TYPE_sawb: Int = 0x73617762

        const val TYPE_udta: Int = 0x75647461

        const val TYPE_meta: Int = 0x6d657461

        const val TYPE_xyz: Int = -0x56878686

        const val TYPE_smta: Int = 0x736d7461

        const val TYPE_chpl: Int = 0x6368706c

        const val TYPE_tref: Int = 0x74726566

        const val TYPE_chap: Int = 0x63686170

        const val TYPE_saut: Int = 0x73617574

        const val TYPE_srfr: Int = 0x73726672

        const val TYPE_keys: Int = 0x6b657973

        const val TYPE_ilst: Int = 0x696c7374

        const val TYPE_mean: Int = 0x6d65616e

        const val TYPE_name: Int = 0x6e616d65

        const val TYPE_data: Int = 0x64617461

        const val TYPE_emsg: Int = 0x656d7367

        const val TYPE_st3d: Int = 0x73743364

        const val TYPE_sv3d: Int = 0x73763364

        const val TYPE_proj: Int = 0x70726f6a

        const val TYPE_camm: Int = 0x63616d6d

        const val TYPE_mett: Int = 0x6d657474

        const val TYPE_it35: Int = 0x69743335

        const val TYPE_alac: Int = 0x616c6163

        const val TYPE_alaw: Int = 0x616c6177

        const val TYPE_ulaw: Int = 0x756c6177

        const val TYPE_Opus: Int = 0x4f707573

        const val TYPE_dOps: Int = 0x644f7073

        const val TYPE_fLaC: Int = 0x664c6143

        const val TYPE_dfLa: Int = 0x64664c61

        const val TYPE_twos: Int = 0x74776f73

        const val TYPE_clli: Int = 0x636c6c69

        const val TYPE_mdcv: Int = 0x6d646376

        const val TYPE_iamf: Int = 0x69616d66

        const val TYPE_iacb: Int = 0x69616362

        const val TYPE_axte: Int = 0x61787465

        const val TYPE_ipcm: Int = 0x6970636d

        const val TYPE_fpcm: Int = 0x6670636d

        const val TYPE_pcmC: Int = 0x70636d43

        const val TYPE_free: Int = 0x66726565

        fun getBoxTypeString(type: Int): String {
            return (""
                    + ((type shr 24) and 0xFF).toChar() + ((type shr 16) and 0xFF).toChar() + ((type shr 8) and 0xFF).toChar() + (type and 0xFF).toChar())
        }
    }
}
