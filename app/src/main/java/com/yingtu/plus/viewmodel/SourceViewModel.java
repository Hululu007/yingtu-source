package com.yingtu.plus.viewmodel;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.yingtu.plus.App;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.api.SiteApi;
import com.yingtu.plus.api.config.VodConfig;
import com.yingtu.plus.bean.Class;
import com.yingtu.plus.bean.Flag;
import com.yingtu.plus.bean.Episode;
import com.yingtu.plus.bean.Movie;
import com.yingtu.plus.bean.MovieSort;
import com.yingtu.plus.bean.Result;
import com.yingtu.plus.bean.Site;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.bean.Vod;
import com.yingtu.plus.bean.VodInfo;
import com.yingtu.plus.util.OkGo;
import com.yingtu.plus.web.WebHomeInlineVodStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 站点数据 ViewModel：首页分类/列表、详情、搜索。
 *
 * 取数走 SiteApi（TVBox 蜘蛛协议），后台线程跑、LiveData.postValue 回主线。
 * sourceKey 为 WebHomeInlineVodStore.KEY 时，详情走内联片库（fm.vodInline
 * 存进来的多集内容），不走站点。
 */
public class SourceViewModel extends ViewModel {

    public final MutableLiveData<List<MovieSort>> sortResult = new MutableLiveData<>();
    public final MutableLiveData<List<Movie>> listResult = new MutableLiveData<>();
    public final MutableLiveData<List<Movie>> searchResult = new MutableLiveData<>();
    public final MutableLiveData<VodInfo> detailResult = new MutableLiveData<>();
    // 首页取数失败时的可读原因（null = 无错误）：界面不再只显示“该源暂无推荐内容”。
    public final MutableLiveData<String> sortError = new MutableLiveData<>();

    private String homeKey;

    /** 首页：当前站的分类与首屏列表。 */
    public void getSort() {
        OkGo.io(() -> {
            sortError.postValue(null);
            try {
                SourceBean source = ApiConfig.get().getHomeSource();
                if (source == null) {
                    sortResult.postValue(null);
                    listResult.postValue(null);
                    return;
                }
                homeKey = source.getKey();
                Site site = VodConfig.get().getSite(homeKey);
                if (site == null) {
                    sortResult.postValue(null);
                    listResult.postValue(null);
                    return;
                }
                Result result = SiteApi.homeContent(site);
                List<MovieSort> sorts = new ArrayList<>();
                if (result.getTypes() != null) {
                    for (Class type : result.getTypes()) {
                        sorts.add(new MovieSort(type.getTypeId(), type.getTypeName()));
                    }
                }
                sortResult.postValue(sorts);
                listResult.postValue(toMovies(homeKey, result.getList()));
            } catch (Exception e) {
                // 之前这里静默吞错：界面只显示“该源暂无推荐内容”，堆栈哪都找不到。
                // 现在记进 crash.log，界面也给一句可读的原因。
                App.logCaught(e);
                sortError.postValue(e.getMessage() == null ? e.toString() : e.getMessage());
                sortResult.postValue(null);
                listResult.postValue(null);
            }
        });
    }

    /** 分类列表分页。 */
    public void getList(String typeId, int page, HashMap<String, String> extend) {
        OkGo.io(() -> {
            try {
                Result result = SiteApi.categoryContent(homeKey, typeId, String.valueOf(page), true, extend);
                listResult.postValue(toMovies(homeKey, result.getList()));
            } catch (Exception e) {
                listResult.postValue(null);
            }
        });
    }

    /** 详情。WebHome 内联片走 WebHomeInlineVodStore，其余走站点。 */
    public void getDetail(String sourceKey, String vodId) {
        OkGo.io(() -> {
            try {
                Vod vod;
                if (WebHomeInlineVodStore.KEY.equals(sourceKey)) {
                    vod = WebHomeInlineVodStore.detail(vodId).getVod();
                } else {
                    vod = SiteApi.detailContent(sourceKey, vodId).getVod();
                }
                detailResult.postValue(vod == null ? null : toVodInfo(sourceKey, vod));
            } catch (Exception e) {
                detailResult.postValue(null);
            }
        });
    }

    /** 搜索。 */
    public void search(String keyword) {
        OkGo.io(() -> {
            try {
                SourceBean source = ApiConfig.get().getHomeSource();
                if (source == null) {
                    searchResult.postValue(null);
                    return;
                }
                Site site = VodConfig.get().getSite(source.getKey());
                if (site == null) {
                    searchResult.postValue(null);
                    return;
                }
                Result result = SiteApi.searchContent(site, keyword, false, "1");
                searchResult.postValue(toMovies(source.getKey(), result.getList()));
            } catch (Exception e) {
                searchResult.postValue(null);
            }
        });
    }

    private List<Movie> toMovies(String key, List<Vod> vods) {
        List<Movie> list = new ArrayList<>();
        if (vods == null) return list;
        for (Vod vod : vods) {
            Movie movie = new Movie(vod.getId(), vod.getName(), vod.getPic(), vod.getRemarks());
            movie.sourceKey = key;
            movie.year = vod.getYear();
            movie.area = vod.getArea();
            movie.type = vod.getTypeName();
            movie.actor = vod.getActor();
            movie.director = vod.getDirector();
            movie.content = vod.getContent();
            list.add(movie);
        }
        return list;
    }

    private VodInfo toVodInfo(String sourceKey, Vod vod) {
        VodInfo info = new VodInfo();
        info.id = vod.getId();
        info.name = vod.getName();
        info.pic = vod.getPic();
        info.note = vod.getRemarks();
        info.year = vod.getYear();
        info.area = vod.getArea();
        info.type = vod.getTypeName();
        info.actor = vod.getActor();
        info.director = vod.getDirector();
        info.des = vod.getContent();
        info.sourceKey = sourceKey;
        for (Flag flag : vod.getFlags()) {
            VodInfo.VodSeriesFlag seriesFlag = new VodInfo.VodSeriesFlag(flag.getFlag());
            for (Episode episode : flag.getEpisodes()) {
                seriesFlag.list.add(new VodInfo.VodSeries(episode.getName(), episode.getUrl()));
            }
            info.seriesFlags.add(seriesFlag);
        }
        return info;
    }
}
