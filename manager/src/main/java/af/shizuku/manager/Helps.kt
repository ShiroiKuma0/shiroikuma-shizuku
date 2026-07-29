package af.shizuku.manager

import af.shizuku.manager.utils.MultiLocaleEntity

object Helps {
    // ---------------------------------------------------------------------------------------
    // FORK: the split here is deliberate — do NOT "de-brand" the wiki links back to our repo.
    //
    // ADB / ADB_ANDROID11 / APPS / ADB_PERMISSION point at UPSTREAM's wiki because that wiki
    // actually has the pages: ours (ShiroiKuma0/shiroikuma-shizuku) has none, so repointing them
    // at our repo turns four "Learn more" buttons back into 404s for the sake of branding.
    // Content links go where the content is; identity links stay ours.
    //
    // HOME and RISH stay on OUR repo — those are identity links, and offering upstream's releases
    // would be actively wrong (different signing key, could never install over ours).
    // See CLAUDE.md, "Fork identity".
    // 白い熊 chose this split on 2026-08-26.
    // ---------------------------------------------------------------------------------------
    // Points at Service-Connection's "Starting via PC ADB" section specifically — this link is
    // shown alongside "requires computer connection" copy, so it should land the reader
    // directly on the PC-adb walkthrough, not the page top (which used to be a dead
    // "Setup" page 404 before that, and even after fixing it to a real page, this exact
    // section didn't exist yet — the link resolved but didn't actually answer what the
    // reader came for).
    val ADB = MultiLocaleEntity().apply {
        put("zh-CN", "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#starting-via-pc-adb")
        put("zh-TW", "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#starting-via-pc-adb")
        put("en", "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#starting-via-pc-adb")
    }

    val ADB_ANDROID11 = MultiLocaleEntity().apply {
        put("zh-CN", "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#starting-via-wireless-adb")
        put("zh-TW", "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#starting-via-wireless-adb")
        put("en", "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#starting-via-wireless-adb")
    }

    // "Supported-apps" doesn't exist as its own page either — Knowledgebase is the closest
    // real landing page until a dedicated compatibility list is written.

    val HOME = MultiLocaleEntity().apply {
        put("en", "https://github.com/ShiroiKuma0/shiroikuma-shizuku/blob/custom/README.md")
    }

    val ADB_PERMISSION = MultiLocaleEntity().apply {
        put("zh-CN", "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#error-reference")
        put("zh-TW", "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#error-reference")
        put("en", "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#error-reference")
    }

    val SUI = MultiLocaleEntity().apply {
        put("en", "https://github.com/RikkaApps/Sui")
    }

    val RISH = MultiLocaleEntity().apply {
        put("en", "https://github.com/ShiroiKuma0/shiroikuma-shizuku/blob/custom/README.md#build")
    }

}
