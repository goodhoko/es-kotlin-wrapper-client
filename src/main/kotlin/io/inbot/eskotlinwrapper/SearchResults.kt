package io.inbot.eskotlinwrapper

import org.elasticsearch.action.search.ClearScrollRequest
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.client.RestHighLevelClient
import org.elasticsearch.client.scroll
import org.elasticsearch.search.SearchHit

/**
 * Abstraction for search results that applies to both scrolling and non scrolling searches.
 */
interface SearchResults<T : Any> {
    /**
     * search hits as a sequence or an empty sequence.
     */
    val searchHits: Sequence<SearchHit>

    /**
     * deserialized objects inside the `searchHits`. Deserialization happens lazily.
     */
    val mappedHits: Sequence<T>
        get() {
            return searchHits.map { modelReaderAndWriter.deserialize(it) }
        }

    /**
     * a sequence of pairs of search hits and their deserialized objects.
     */
    val hits: Sequence<Pair<SearchHit, T?>>
        get() {
            return searchHits.map {
                Pair(it, modelReaderAndWriter.deserialize(it))
            }
        }

    /**
     * The total number of hits for the query.
     */
    val totalHits: Long

    /**
     * the original search response.
     */
    val searchResponse: SearchResponse

    /**
     * the model reader and writer used for deserialization.
     */
    val modelReaderAndWriter: ModelReaderAndWriter<T>
}

/**
 * Represents a page of search results. Returned for non scrolling searches.
 */
class PagedSearchResults<T : Any>(
    override val searchResponse: SearchResponse,
    override val modelReaderAndWriter: ModelReaderAndWriter<T>
) : SearchResults<T> {

    override val searchHits: Sequence<SearchHit>
        get() {
            return searchResponse.hits?.hits?.asSequence() ?: emptySequence()
        }

    override val totalHits: Long
        get() {
            return searchResponse.hits?.totalHits?.value ?: 0
        }
}

/**
 * Represents scrolling search results. Accessing the [searchHits] causes pages of results to be retrieved lazily.
 *
 * Note. you can only consume the sequence once.
 */
class ScrollingSearchResults<T : Any>(
    override val searchResponse: SearchResponse,
    override val modelReaderAndWriter: ModelReaderAndWriter<T>,
    val restHighLevelClient: RestHighLevelClient,
    val scrollTtlInMinutes: Long,
    val defaultRequestOptions: RequestOptions = RequestOptions.DEFAULT
) : SearchResults<T> {

    override val searchHits: Sequence<SearchHit>
        get() {
            return responses().flatMap { response ->
                response.hits?.asSequence() ?: emptySequence()
            }
        }

    override val totalHits: Long
        get() {
            return searchResponse.hits?.totalHits?.value ?: 0
        }

    private fun responses(): Sequence<SearchResponse> {
        return generateSequence(
            // FIXME it currently seems impossible to do a suspending version of this
            seed = searchResponse,
            nextFunction = {
                val currentScrollId = it.scrollId
                if (currentScrollId != null && it.hits.hits != null && it.hits.hits.size > 0) {
                    restHighLevelClient.scroll(currentScrollId, scrollTtlInMinutes, defaultRequestOptions)
                } else {
                    val clearScrollRequest = ClearScrollRequest()
                    clearScrollRequest.addScrollId(currentScrollId)
                    restHighLevelClient.clearScroll(clearScrollRequest, defaultRequestOptions)
                    null
                }
            })
    }
}
