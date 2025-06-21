package HellBot.extensions

import HellBot.i18n.Translations
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.publicSlashCommand
import khttp.responses.Response

class FunExtensions : Extension() {

	/**
	 * The name of the extension, used for identification and loading.
	 */
	override val name = "fun"

	override suspend fun setup() {
		// Setup code for the FunExtensions can be added here

		publicSlashCommand {
			name = Translations.Fun.Commands.Dog.name
			description = Translations.Fun.Commands.Dog.description

			action {
				val response: Response = khttp.get("https://random.dog/woof.json")
				if (response.statusCode == 200) {
					val json = response.jsonObject
					val imageUrl = json.getString("url")
					respond {
						content = imageUrl
					}
				}
			}

		}

	}

}
