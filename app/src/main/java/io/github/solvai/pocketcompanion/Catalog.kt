package io.github.solvai.pocketcompanion

/** A free stock avatar published by duix.com. Previews are in assets/avatars. */
data class AvatarOption(val id: String, val name: String, val url: String, val sizeMb: Int)

/** A Kokoro v1.0 voice. They all read the English lexicon, so these give an accent, not another language. */
data class VoiceOption(val id: String, val name: String, val language: String, val speakerId: Int)

object Catalog {
    val avatars = listOf(
        AvatarOption("yiyao", "Yi Yao", "https://digital-public.obs.cn-east-3.myhuaweicloud.com/duix/digital/model/1719194263441/yiyao_20240418.zip", 271),
        AvatarOption("sophie", "Sophie", "https://digital-public.obs.cn-east-3.myhuaweicloud.com/duix/digital/model/1719193425133/sufei_20240409.zip", 45),
        AvatarOption("murongxiao", "Mu Rong Xiao", "https://digital-public.obs.cn-east-3.myhuaweicloud.com/duix/digital/model/1719193516102/murongxiao_20240410.zip", 397),
        AvatarOption("coldflame", "Cold Flame", "https://digital-public.obs.cn-east-3.myhuaweicloud.com/duix/digital/model/1719193451931/lengyan_20240407.zip", 149),
        AvatarOption("amelia", "Amelia", "https://digital-public.obs.cn-east-3.myhuaweicloud.com/duix/digital/model/1719193625986/amelia_20240411.zip", 408),
        AvatarOption("zhaoya", "Zhao Ya", "https://digital-public.obs.cn-east-3.myhuaweicloud.com/duix/digital/model/1719194234727/zhaoya_20240411.zip", 111),
        AvatarOption("xinyan", "Xin Yan", "https://digital-public.obs.cn-east-3.myhuaweicloud.com/duix/digital/model/1719194373660/xinyan_20240411.zip", 401),
        AvatarOption("xiaoxuan", "Xiao Xuan", "https://digital-public.obs.cn-east-3.myhuaweicloud.com/duix/digital/model/1719194313414/xiaoxuan_20240418.zip", 356),
        AvatarOption("siyao", "Si Yao", "https://digital-public.obs.cn-east-3.myhuaweicloud.com/duix/digital/model/1719194450521/siyao_20240418.zip", 410),
        AvatarOption("shiya", "Shi Ya", "https://digital-public.obs.cn-east-3.myhuaweicloud.com/duix/digital/model/1719194516880/shiya_20240409.zip", 61),
        AvatarOption("dearsister", "Dear Sister", "https://digital-public.obs.cn-east-3.myhuaweicloud.com/duix/digital/model/1719194007931/bendi1_0329.zip", 58),
    )

    val voices = listOf(
        VoiceOption("jf_alpha", "Alpha", "Japanese", 37),
        VoiceOption("jf_gongitsune", "Gongitsune", "Japanese", 38),
        VoiceOption("jf_nezumi", "Nezumi", "Japanese", 39),
        VoiceOption("jf_tebukuro", "Tebukuro", "Japanese", 40),
        VoiceOption("zf_xiaobei", "Xiaobei", "Mandarin", 45),
        VoiceOption("zf_xiaoni", "Xiaoni", "Mandarin", 46),
        VoiceOption("zf_xiaoxiao", "Xiaoxiao", "Mandarin", 47),
        VoiceOption("zf_xiaoyi", "Xiaoyi", "Mandarin", 48),
        VoiceOption("af_heart", "Heart", "American", 3),
        VoiceOption("af_bella", "Bella", "American", 2),
        VoiceOption("af_nicole", "Nicole", "American", 6),
        VoiceOption("af_sky", "Sky", "American", 10),
        VoiceOption("bf_emma", "Emma", "British", 21),
        VoiceOption("bf_isabella", "Isabella", "British", 22),
    )

    fun avatar(id: String?): AvatarOption = avatars.firstOrNull { it.id == id } ?: avatars.first()

    fun voice(id: String?): VoiceOption = voices.firstOrNull { it.id == id } ?: voices.first()
}
