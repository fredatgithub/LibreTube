package com.github.libretube.ui.models

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toID
import com.github.libretube.util.PreferenceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SubscriptionsViewModel : ViewModel() {
    var errorResponse = MutableLiveData<Boolean>().apply {
        value = false
    }

    var videoFeed = MutableLiveData<List<com.github.libretube.api.obj.StreamItem>?>().apply {
        value = null
    }

    var subscriptions = MutableLiveData<List<com.github.libretube.api.obj.Subscription>?>().apply {
        value = null
    }

    fun fetchFeed() {
        CoroutineScope(Dispatchers.IO).launch {
            val videoFeed = try {
                if (PreferenceHelper.getToken() != "") {
                    RetrofitInstance.authApi.getFeed(
                        PreferenceHelper.getToken()
                    )
                } else {
                    RetrofitInstance.authApi.getUnauthenticatedFeed(
                        SubscriptionHelper.getFormattedLocalSubscriptions()
                    )
                }
            } catch (e: Exception) {
                errorResponse.postValue(true)
                Log.e(TAG(), e.toString())
                return@launch
            }
            this@SubscriptionsViewModel.videoFeed.postValue(videoFeed)
            if (videoFeed.isNotEmpty()) {
                // save the last recent video to the prefs for the notification worker
                PreferenceHelper.setLatestVideoId(videoFeed[0].url!!.toID())
            }
        }
    }

    fun fetchSubscriptions() {
        CoroutineScope(Dispatchers.IO).launch {
            val subscriptions = try {
                if (PreferenceHelper.getToken() != "") {
                    RetrofitInstance.authApi.subscriptions(
                        PreferenceHelper.getToken()
                    )
                } else {
                    RetrofitInstance.authApi.unauthenticatedSubscriptions(
                        SubscriptionHelper.getFormattedLocalSubscriptions()
                    )
                }
            } catch (e: Exception) {
                errorResponse.postValue(true)
                Log.e(TAG(), e.toString())
                return@launch
            }
            this@SubscriptionsViewModel.subscriptions.postValue(subscriptions)
        }
    }
}
