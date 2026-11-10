package com.yingtu.plus.viewmodel;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.yingtu.plus.App;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.api.SiteApi;
import com.yingtu.plus.api.config.VodConfig;
import com.yingtu.plus.bean.Class;
import com.yingtu.plus.bean.Filter;
import com.yingtu.plus.bean.Value;
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
import com.yingtu.plus.util.VodResultCache;
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
                String newHomeKey = source.getKey();
                // dm16：切站时清掉新站的旧缓存条目（含此前瞬时失败钉住的空结果），强制现取；
                // 同站重进 homeKey 不变，不清，缓存照常用。
                if (!newHomeKey.equals(homeKey)) VodResultCache.clearSite(newHomeKey);
                homeKey = newHomeKey;
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
                        MovieSort sort = new MovieSort(type.getTypeId(), type.getTypeName());
                        for (Filter filter : type.getFilters()) {
                            if (filter == null) continue;
                            MovieSort.Filter group = new MovieSort.Filter(filter.getKey(), filter.getName());
                            for (Value value : filter.getValue()) {
                                if (value == null) continue;
                                group.values.add(new MovieSort.Value(value.getN(), value.getV()));
                            }
                            sort.filters.add(group);
                        }
                        sorts.add(sort);
                    }
                }
                sortResult.postValue(sorts);
                listResult.postValue(toMovies(homeKey, result.getList()));
            } catch (Throwable e) {
                // 死站蜘蛛可能抛 Error（如栈溢出、类找不到）：只接 Exception 会漏网，
                // 未捕获的 Error 会杀掉后台线程直至进程闪退。这里接 Throwable，
                // 退化成“加载失败：原因”，不杀进程。
                App.logCaught(e);
                sortError.postValue(e.getMessage() == null ? e.toString() : e.getMessage());
                sortResult.postValue(null);
                listResult.postValue(null);
            }
        });
    }

    /** 混合首页：激活的混合规则逐站取首页推荐并去重合并；分类 Tab 不出（各站分类 id 会撞）。 */
    public void getSortMixed(List<SourceBean> members) {
        OkGo.io(() -> {
            sortError.postValue(null);
            try {
                List<Movie> merged = new ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                String firstKey = null;
                for (SourceBean bean : members) {
                    if (bean == null) continue;
                    try {
                        Site site = VodConfig.get().getSite(bean.getKey());
                        if (site == null) continue;
                        if (firstKey == null) firstKey = bean.getKey();
                        Result result = SiteApi.homeContent(site);
                        if (result == null || result.getList() == null) continue;
                        for (Movie m : toMovies(bean.getKey(), result.getList())) {
                            String dedup = m.sourceKey + "|" + m.vodId;
                            if (seen.add(dedup)) merged.add(m);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                homeKey = firstKey;
                sortResult.postValue(new ArrayList<>());
                listResult.postValue(merged);
            } catch (Throwable e) {
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
            } catch (Throwable e) {
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
            } catch (Throwable e) {
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
            } catch (Throwable e) {
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
            movie.douban = vod.isDouban();
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
        info.stills = vod.getStills();
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
