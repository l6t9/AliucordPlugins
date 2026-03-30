version = "1.0.2"
description = "Removes emojis and symbols from channel names, and more"

aliucord.changelog.set("""
    # 1.0.1
    * now works with threads sucessfully (doesnt remove spaces or dashes)
    * you can now whitelist a server from the menu, and show/hide this toggle in the settings
    * there is now a normalization setting which turns fancy characters into standard ones
""".trimIndent())

aliucord {
    deploy.set(true)
}
