package id.hanifalfaqih.aienglishinterview.data.remote

import id.hanifalfaqih.aienglishinterview.core.network.ApiConfig
import id.hanifalfaqih.aienglishinterview.core.network.RetrofitFactory

/**
 * Single wiring point for the API service. Screens and view models never
 * build Retrofit themselves; tests inject fakes instead of using this.
 */
object ApiProvider {
    val api: InterviewApi by lazy {
        RetrofitFactory.newRetrofit(ApiConfig.baseUrl).create(InterviewApi::class.java)
    }
}
