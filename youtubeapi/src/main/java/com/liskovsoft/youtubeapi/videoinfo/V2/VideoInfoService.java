package com.liskovsoft.youtubeapi.videoinfo.V2;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;
import com.liskovsoft.youtubeapi.innertube.initialresponse.InitialResponseService;
import com.liskovsoft.youtubeapi.videoinfo.VideoInfoServiceBase;
import com.liskovsoft.youtubeapi.videoinfo.models.CaptionTrack;
import com.liskovsoft.youtubeapi.videoinfo.models.TranslationLanguage;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfoHls;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfoReel;

import java.util.List;

import retrofit2.Call;

public class VideoInfoService extends VideoInfoServiceBase {
    private static final String TAG = VideoInfoService.class.getSimpleName();
    private static final AppClient IOS_CLIENT = AppClient.VISIONOS;
    private static final AppClient TV_CLIENT = AppClient.TV_DOWNGRADED;
    private static final AppClient WEB_CLIENT = AppClient.WEB_EMBED;
    private static VideoInfoService sInstance;
    private final VideoInfoApi mVideoInfoApi;
    private boolean mUseAuth;
    private volatile List<TranslationLanguage> mCachedTranslationLanguages;
    private boolean mTranslationLanguagesFetched;

    private VideoInfoService() {
        mVideoInfoApi = RetrofitHelper.create(VideoInfoApi.class);
    }

    public static VideoInfoService instance() {
        if (sInstance == null) {
            sInstance = new VideoInfoService();
        }

        return sInstance;
    }

    @Nullable
    public VideoInfo getVideoInfo(AppClient client, String videoId, String clickTrackingParams) {
        if (videoId == null) {
            return null;
        }

        mUseAuth = true;

        long t0 = System.currentTimeMillis(); // LOCAL: timing diagnostics
        VideoInfo result = getVideoInfoWithRentFix(client, videoId, clickTrackingParams);
        Log.d(TAG, "Timing %s: player request %d ms", client, System.currentTimeMillis() - t0);

        if (result == null) {
            Log.e(TAG, "Can't get video info. videoId: %s", videoId);
            return null;
        }

        long t1 = System.currentTimeMillis();
        applyFixesIfNeeded(result, videoId, clickTrackingParams);
        Log.d(TAG, "Timing %s: fixes %d ms", client, System.currentTimeMillis() - t1);
        t1 = System.currentTimeMillis();

        transformFormats(result);
        Log.d(TAG, "Timing %s: transform formats %d ms", client, System.currentTimeMillis() - t1);

        return result;
    }

    public VideoInfo getAuthVideoInfo(String videoId, String clickTrackingParams) {
        if (videoId == null) {
            return null;
        }

        mUseAuth = true;

        // Only the TV client supports auth features
        return getVideoInfoReal(TV_CLIENT, videoId, clickTrackingParams);
    }

    public void switchNextSubtitle() {
        CaptionTrack.sFormat = Helpers.getNextValue(CaptionTrack.CaptionFormat.values(), CaptionTrack.sFormat);
    }

    private VideoInfo getVideoInfoWithRentFix(AppClient client, String videoId, String clickTrackingParams) {
        VideoInfo result = getVideoInfoReal(client, videoId, clickTrackingParams);

        if (result != null && result.isRent()) {
            Log.e(TAG, "Found rent content. Show trailer instead...");
            result = getVideoInfoReal(client, result.getTrailerVideoId(), clickTrackingParams);
        }

        return result;
    }

    private VideoInfo getVideoInfoReal(AppClient client, String videoId, String clickTrackingParams) {
        VideoInfo result;

        if (client == AppClient.INITIAL) {
            result = InitialResponseService.getVideoInfo(videoId, client.isAuthSupported() && mUseAuth);
        } else {
            String videoInfoQuery = VideoInfoApiHelper.getVideoInfoQuery(client, videoId, clickTrackingParams);
            result = getVideoInfo(client, videoInfoQuery);
        }

        if (result != null) {
            result.setClient(client);
        }

        logFormatSummary(client, videoId, result);

        return result;
    }

    private static void logFormatSummary(AppClient client, String videoId, VideoInfo result) {
        if (result == null) {
            Log.d(TAG, "Video info %s %s: no response", client, videoId);
            return;
        }

        int withUrl = 0, broken = 0, maxHeight = 0, maxFps = 0;

        if (result.getAdaptiveFormats() != null) {
            for (com.liskovsoft.youtubeapi.videoinfo.models.formats.AdaptiveVideoFormat format : result.getAdaptiveFormats()) {
                if (format.isBroken()) {
                    broken++;
                    continue;
                }
                withUrl++;
                if (format.getHeight() > maxHeight) {
                    maxHeight = format.getHeight();
                    maxFps = format.getFps();
                }
            }
        }

        Log.d(TAG, "Video info %s %s: status=%s, unplayable=%s, formats=%s (no url: %s), max=%sp%s, sabr=%s",
                client, videoId, result.getPlayabilityStatus(), result.isUnplayable(), withUrl, broken, maxHeight, maxFps,
                result.getServerAbrStreamingUrl() != null);
    }

    private VideoInfo getVideoInfo(AppClient client, String videoInfoQuery) {
        boolean auth = client.isAuthSupported() && mUseAuth;

        if (client.isReelClient()) {
            Call<VideoInfoReel> wrapper = mVideoInfoApi.getVideoInfoReel(videoInfoQuery, mAppService.getVisitorData(),
                    client.getUserAgent(), client.getInnerTubeName(), client.getClientVersion());
            return getVideoInfoReel(wrapper, auth);
        }

        Call<VideoInfo> wrapper = mVideoInfoApi.getVideoInfo(videoInfoQuery, mAppService.getVisitorData(),
                client.getUserAgent(), client.getInnerTubeName(), client.getClientVersion());
        return getVideoInfo(wrapper, auth);
    }

    private @Nullable VideoInfo getVideoInfo(Call<VideoInfo> wrapper, boolean auth) {
        VideoInfo videoInfo = RetrofitHelper.get(wrapper, auth);

        if (videoInfo == null) {
            return null;
        }

        videoInfo.setAuth(auth);

        return videoInfo;
    }

    private @Nullable VideoInfo getVideoInfoReel(Call<VideoInfoReel> wrapper, boolean auth) {
        VideoInfoReel videoInfo = RetrofitHelper.get(wrapper, auth);

        if (videoInfo == null || videoInfo.getVideoInfo() == null) {
            return null;
        }

        videoInfo.getVideoInfo().setAuth(auth);

        return videoInfo.getVideoInfo();
    }

    private VideoInfoHls getVideoInfoIOSHls(String videoId, String clickTrackingParams) {
        String videoInfoQuery = VideoInfoApiHelper.getVideoInfoQuery(IOS_CLIENT, videoId, clickTrackingParams);
        return getVideoInfoHls(IOS_CLIENT, videoInfoQuery);
    }

    private VideoInfoHls getVideoInfoHls(AppClient client, String videoInfoQuery) {
        Call<VideoInfoHls> wrapper = mVideoInfoApi.getVideoInfoHls(videoInfoQuery, mAppService.getVisitorData(),
                client.getUserAgent(), client.getInnerTubeName(), client.getClientVersion());

        return RetrofitHelper.get(wrapper, client.isAuthSupported() && mUseAuth);
    }

    private void applyFixesIfNeeded(VideoInfo result, String videoId, String clickTrackingParams) {
        if (result == null || result.isUnplayable()) {
            return;
        }

        boolean oldUseAuth = mUseAuth;

        if (shouldObtainExtendedFormats(result) || result.isStoryboardBroken()) {
            Log.d(TAG, "Enable high bitrate formats...");
            mUseAuth = false;
            VideoInfoHls videoInfoHls = getVideoInfoIOSHls(videoId, clickTrackingParams);
            if (videoInfoHls != null && shouldObtainExtendedFormats(result)) {
                result.setHlsManifestUrl(videoInfoHls.getHlsManifestUrl());
            }
            if (videoInfoHls != null && result.isStoryboardBroken()) {
                result.setStoryboardSpec(videoInfoHls.getStoryboardSpec());
            }
        }

        // TV and others has a limited number of auto generated subtitles
        if (needMoreSubtitles(result)) {
            Log.d(TAG, "Enable full list of auto generated subtitles...");

            // LOCAL: once per session and in the background. The list was re-fetched before every video whenever
            // it had less than 100 entries, blocking the start (0.5 s, up to 5.7 s on the first video of a session).
            // WEB_DUMMY never uses auth, so mUseAuth isn't touched. The first video gets the short list.
            if (!mTranslationLanguagesFetched) {
                mTranslationLanguagesFetched = true;
                new Thread(() -> {
                    try {
                        VideoInfo webInfo = getVideoInfoReal(AppClient.WEB_DUMMY, videoId, clickTrackingParams);
                        if (webInfo != null) {
                            mCachedTranslationLanguages = webInfo.getTranslationLanguages();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }, "TranslationLanguages").start();
            }

            if (mCachedTranslationLanguages != null) {
                result.setTranslationLanguages(mCachedTranslationLanguages);
            }
        }

        mUseAuth = oldUseAuth;
    }

    private static boolean shouldObtainExtendedFormats(VideoInfo result) {
        return getData().isFormatEnabled(MediaServiceData.FORMATS_EXTENDED_HLS) && result.isExtendedHlsFormatsBroken();
    }

    private static boolean needMoreSubtitles(VideoInfo videoInfo) {
        return videoInfo != null && videoInfo.hasSubtitles() && (videoInfo.getTranslationLanguages() == null || videoInfo.getTranslationLanguages().size() < 100);
    }
}
