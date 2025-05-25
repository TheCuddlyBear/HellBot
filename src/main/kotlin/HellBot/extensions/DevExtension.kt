package HellBot.extensions

import HellBot.i18n.Translations
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.ephemeralSlashCommand
import dev.kordex.core.extensions.publicSlashCommand
import kotlin.system.exitProcess

class DevExtension : Extension() {
	override val name = "dev"

	override suspend fun setup() {
		ephemeralSlashCommand {
			name = Translations.Dev.Commands.Botstop.name
			description = Translations.Dev.Commands.Botstop.description
			action {
				// Only allow command to be run by the bot owner
				if (event.interaction.user.id.value.toLong() != 206879635059900417) {
					respond {
						content = "You are not allowed to stop the bot!"
					}
					return@action
				}
				respond {
					ephemeral
					content = "Stopping the bot...."
				}
				bot.stop()
				exitProcess(0)
			}

		}
	}
}
