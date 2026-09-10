package com.tricrotism.cryon.paper.api.bedrock

/**
 * The picture beside a [FormButton], drawn by the Bedrock client.
 */
sealed interface FormImage {

    /**
     * A texture inside the client's active resource pack, such as `textures/cryon/types/fire`.
     */
    data class Path(val path: String) : FormImage

    data class Url(val url: String) : FormImage
}
