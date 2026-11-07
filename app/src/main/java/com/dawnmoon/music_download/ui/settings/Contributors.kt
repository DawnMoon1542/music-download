package com.dawnmoon.music_download.ui.settings

/**
 * [ChKSz API 贡献者](https://api.chksz.com/#contributors) 的静态名单。
 *
 * 内容对应官网 `/data/contributors.json`。应用不在运行时请求这份名单，避免鸣谢页依赖网络。
 */
internal data class Contributor(
    val name: String,
    val role: String,
    val description: String,
    val badge: ContributorBadge,
    val profileUrl: String,
    val avatarUrl: String,
)

internal enum class ContributorBadge {
    Maintainer,
    Sponsor,
    Provider,
}

internal val ChkszContributors: List<Contributor> = listOf(
    Contributor(
        name = "ChKSz",
        role = "项目作者与维护者",
        description = "负责 ChKSz API 的开发、维护与服务运营。",
        badge = ContributorBadge.Maintainer,
        profileUrl = "https://linux.do/u/chksz",
        avatarUrl = "https://cdn.ldstatic.com/user_avatar/linux.do/chksz/96/825240_2.png",
    ),
    Contributor(
        name = "欣欣 | 林可欣",
        role = "LDC 赞助者",
        description = "赞助 1000 LDC，用于支持更宽裕的用户免费额度。",
        badge = ContributorBadge.Sponsor,
        profileUrl = "https://linux.do/u/stellafortuna",
        avatarUrl = "https://cdn.ldstatic.com/user_avatar/linux.do/stellafortuna/96/1230359_2.png",
    ),
    Contributor(
        name = "轩宇",
        role = "服务器 赞助者",
        description = "为项目提供小鸡服务器，使能够正常运行",
        badge = ContributorBadge.Sponsor,
        profileUrl = "https://linux.do/u/wylangyue",
        avatarUrl = "https://cdn.ldstatic.com/user_avatar/linux.do/wylangyue/288/537711_2.png",
    ),
    Contributor(
        name = "是青旨啊",
        role = "上游 赞助者",
        description = "为网易云中低音质与QQ音乐提供稳定上游号池",
        badge = ContributorBadge.Provider,
        profileUrl = "https://linux.do/u/sayqz",
        avatarUrl = "https://cdn.ldstatic.com/user_avatar/linux.do/sayqz/288/1476689_2.gif",
    ),
)
