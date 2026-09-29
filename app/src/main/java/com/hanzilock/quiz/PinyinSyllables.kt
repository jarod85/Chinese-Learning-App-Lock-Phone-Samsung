package com.hanzilock.quiz

/** The Mandarin syllable inventory (ü written as v) and a splitter for run-together pinyin. */
object PinyinSyllables {
    val ALL: Set<String> = """
        a ai an ang ao e ei en eng er o ou
        ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu
        pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu
        ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu
        fa fan fang fei fen feng fo fou fu
        da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo
        ta tai tan tang tao te tei teng ti tian tiao tie ting tong tou tu tuan tui tun tuo
        na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nun nuo nv nve nue
        la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lv lve lue
        ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo
        ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo
        ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo
        ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun
        qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun
        xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun
        zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo
        cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo
        sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo
        ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo
        za zai zan zang zao ze zei zen zeng zi zong zou zu zuan zui zun zuo
        ca cai can cang cao ce cen ceng ci cong cou cu cuan cui cun cuo
        sa sai san sang sao se sen seng si song sou su suan sui sun suo
        ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun
        wa wai wan wang wei wen weng wo wu
        m n ng hm hng r
    """.trim().split(Regex("\\s+")).toSet()

    private val RARE = setOf("m", "n", "ng", "hm", "hng", "r")

    /**
     * Splits [letters] (tone-less pinyin, ü as v) into syllable index ranges, or null if it is not
     * pinyin. [mustEndAt] holds letter indexes where a syllable has to end because the user typed a
     * tone number there. Among valid splits it prefers fewer syllables and avoids syllables that
     * start with a/o/e mid-word (standard pinyin would put an apostrophe there): "fangan" -> fan gan.
     */
    fun segment(letters: String, mustEndAt: Set<Int>): List<IntRange>? {
        val n = letters.length
        if (n == 0) return emptyList()
        val cost = DoubleArray(n + 1) { Double.MAX_VALUE }
        val prev = IntArray(n + 1) { -1 }
        cost[0] = 0.0
        for (i in 0 until n) {
            if (cost[i] == Double.MAX_VALUE) continue
            for (len in 1..minOf(6, n - i)) {
                val end = i + len
                if (end - 1 > i && (i + 1 until end).any { it in mustEndAt }) break
                val syllable = letters.substring(i, end)
                if (syllable !in ALL) continue
                var c = cost[i] + 1.0
                if (i > 0 && syllable[0] in "aoe") c += 0.5
                if (syllable in RARE) c += 0.9
                if (c < cost[end]) {
                    cost[end] = c
                    prev[end] = i
                }
            }
        }
        if (cost[n] == Double.MAX_VALUE) return null
        val out = ArrayList<IntRange>()
        var end = n
        while (end > 0) {
            val start = prev[end]
            out.add(start until end)
            end = start
        }
        return out.reversed()
    }
}
