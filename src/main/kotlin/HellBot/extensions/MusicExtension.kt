package HellBot.extensions

import HellBot.i18n.Translations
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.publicSlashCommand
import dev.kordex.core.i18n.withContext
import dev.schlaubi.lavakord.kord.lavakord

class MusicExtension() : Extension() {
	override val name = "music"

	val lavalink = this.bot.kordRef.lavakord {
		plugins {
			install(LavaSrc)
		}
	}

	override suspend fun setup() {
		publicSlashCommand {
			name = Translations.Music.Commands.Play.name
			description = Translations.Music.Commands.Play.description
			action {
				respond {
					content = Translations.Music.Commands.Play.response
						.withContext(this@action)
						.translateNamed(
							"title" to "Hello!",
							"author" to event.interaction.user.mention
						)
				}
			}
		}
	}


}
