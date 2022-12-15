package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.text.Html;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.ApiConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Part;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityDetailBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.PlayerEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.net.Callback;
import com.fongmi.android.tv.net.OkHttp;
import com.fongmi.android.tv.player.ExoUtil;
import com.fongmi.android.tv.player.Players;
import com.fongmi.android.tv.ui.custom.CustomKeyDownVod;
import com.fongmi.android.tv.ui.custom.TrackSelectionDialog;
import com.fongmi.android.tv.ui.custom.dialog.DescDialog;
import com.fongmi.android.tv.ui.presenter.ArrayPresenter;
import com.fongmi.android.tv.ui.presenter.EpisodePresenter;
import com.fongmi.android.tv.ui.presenter.FlagPresenter;
import com.fongmi.android.tv.ui.presenter.ParsePresenter;
import com.fongmi.android.tv.ui.presenter.PartPresenter;
import com.fongmi.android.tv.ui.presenter.SearchPresenter;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Prefers;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Traffic;
import com.fongmi.android.tv.utils.Utils;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.StyledPlayerView;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Response;
import tv.danmaku.ijk.media.player.ui.IjkVideoView;

public class DetailActivity extends BaseActivity implements CustomKeyDownVod.Listener, ArrayPresenter.OnClickListener, Clock.Callback {

    private ActivityDetailBinding mBinding;
    private ViewGroup.LayoutParams mFrameParams;
    private ArrayObjectAdapter mFlagAdapter;
    private ArrayObjectAdapter mArrayAdapter;
    private ArrayObjectAdapter mEpisodeAdapter;
    private ArrayObjectAdapter mParseAdapter;
    private ArrayObjectAdapter mPartAdapter;
    private ArrayObjectAdapter mSearchAdapter;
    private EpisodePresenter mEpisodePresenter;
    private PartPresenter mPartPresenter;
    private CustomKeyDownVod mKeyDown;
    private ExecutorService mExecutor;
    private SiteViewModel mViewModel;
    private boolean mFullscreen;
    private History mHistory;
    private Players mPlayers;
    private int mCurrent;
    private Runnable mR1;
    private Runnable mR2;
    private Runnable mR3;

    public static void start(Activity activity, String id) {
        start(activity, ApiConfig.get().getHome().getKey(), id);
    }

    public static void start(Activity activity, String key, String id) {
        start(activity, key, id, false);
    }

    public static void start(Activity activity, String key, String id, boolean clear) {
        Intent intent = new Intent(activity, DetailActivity.class);
        if (clear) intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("key", key);
        intent.putExtra("id", id);
        activity.startActivityForResult(intent, 1000);
    }

    private String getKey() {
        return getIntent().getStringExtra("key");
    }

    private String getId() {
        return getIntent().getStringExtra("id");
    }

    private String getHistoryKey() {
        return getKey().concat(AppDatabase.SYMBOL).concat(getId());
    }

    private Site getSite() {
        return ApiConfig.get().getSite(getKey());
    }

    private Vod.Flag getVodFlag() {
        return (Vod.Flag) mFlagAdapter.get(mBinding.flag.getSelectedPosition());
    }

    private int getEpisodePosition() {
        for (int i = 0; i < mEpisodeAdapter.size(); i++) if (((Vod.Flag.Episode) mEpisodeAdapter.get(i)).isActivated()) return i;
        return 0;
    }

    private int getPlayerType() {
        return mHistory != null && mHistory.getPlayer() != -1 ? mHistory.getPlayer() : getSite().getPlayerType() != -1 ? getSite().getPlayerType() : Prefers.getPlayer();
    }

    private StyledPlayerView getExo() {
        return Prefers.getRender() == 0 ? mBinding.surface : mBinding.texture;
    }

    private IjkVideoView getIjk() {
        return mBinding.ijk;
    }

    private boolean isVisible(View view) {
        return view.getVisibility() == View.VISIBLE;
    }

    private boolean isGone(View view) {
        return view.getVisibility() == View.GONE;
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityDetailBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView() {
        mKeyDown = CustomKeyDownVod.create(this);
        mFrameParams = mBinding.video.getLayoutParams();
        mBinding.progressLayout.showProgress();
        mPlayers = new Players().init();
        mR1 = this::hideControl;
        mR2 = this::hideCenter;
        mR3 = this::setTraffic;
        setRecyclerView();
        setVideoView();
        setViewModel();
        getDetail();
    }

    @Override
    protected void initEvent() {
        mBinding.control.seek.setListener(mPlayers);
        mBinding.desc.setOnClickListener(view -> onDesc());
        mBinding.keep.setOnClickListener(view -> onKeep());
        mBinding.video.setOnClickListener(view -> onVideo());
        mBinding.control.next.setOnClickListener(view -> checkNext());
        mBinding.control.prev.setOnClickListener(view -> checkPrev());
        mBinding.control.scale.setOnClickListener(view -> onScale());
        mBinding.control.speed.setOnClickListener(view -> onSpeed());
        mBinding.control.player.setOnClickListener(view -> onPlayer());
        mBinding.control.decode.setOnClickListener(view -> onDecode());
        mBinding.control.tracks.setOnClickListener(view -> onTracks());
        mBinding.control.ending.setOnClickListener(view -> onEnding());
        mBinding.control.opening.setOnClickListener(view -> onOpening());
        mBinding.control.replay.setOnClickListener(view -> getPlayer(true));
        mBinding.control.speed.setOnLongClickListener(view -> onSpeedLong());
        mBinding.control.ending.setOnLongClickListener(view -> onEndingReset());
        mBinding.control.opening.setOnLongClickListener(view -> onOpeningReset());
        mBinding.flag.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (mFlagAdapter.size() > 0) setFlagActivated((Vod.Flag) mFlagAdapter.get(position));
            }
        });
        mBinding.array.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (mEpisodeAdapter.size() > 20 && position > 1) mBinding.episode.setSelectedPosition((position - 2) * 20);
            }
        });
    }

    private void setRecyclerView() {
        mBinding.flag.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.flag.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.flag.setAdapter(new ItemBridgeAdapter(mFlagAdapter = new ArrayObjectAdapter(new FlagPresenter(this::setFlagActivated))));
        mBinding.episode.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.episode.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.episode.setAdapter(new ItemBridgeAdapter(mEpisodeAdapter = new ArrayObjectAdapter(mEpisodePresenter = new EpisodePresenter(this::setEpisodeActivated))));
        mBinding.array.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.array.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.array.setAdapter(new ItemBridgeAdapter(mArrayAdapter = new ArrayObjectAdapter(new ArrayPresenter(this))));
        mBinding.part.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.part.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.part.setAdapter(new ItemBridgeAdapter(mPartAdapter = new ArrayObjectAdapter(mPartPresenter = new PartPresenter(this::initSearch))));
        mBinding.search.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.search.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.search.setAdapter(new ItemBridgeAdapter(mSearchAdapter = new ArrayObjectAdapter(new SearchPresenter(this::getDetail))));
        mBinding.control.parse.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.control.parse.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.control.parse.setAdapter(new ItemBridgeAdapter(mParseAdapter = new ArrayObjectAdapter(new ParsePresenter(this::setParseActivated))));
        mParseAdapter.setItems(ApiConfig.get().getParses(), null);
    }

    private void setPlayerView() {
        mBinding.control.player.setText(mPlayers.getPlayerText());
        getExo().setVisibility(mPlayers.isExo() ? View.VISIBLE : View.GONE);
        getIjk().setVisibility(mPlayers.isIjk() ? View.VISIBLE : View.GONE);
    }

    private void setDecodeView() {
        mBinding.control.decode.setText(mPlayers.getDecodeText());
    }

    private void setVideoView() {
        mPlayers.setupIjk(getIjk());
        mPlayers.setupExo(getExo());
        setScale(Prefers.getVodScale());
        getIjk().setRender(Prefers.getRender());
        getExo().getSubtitleView().setStyle(ExoUtil.getCaptionStyle());
    }

    private void setScale(int scale) {
        getExo().setResizeMode(scale);
        getIjk().setResizeMode(scale);
        mBinding.control.scale.setText(ResUtil.getStringArray(R.array.select_scale)[scale]);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.player.observe(this, result -> {
            boolean useParse = (result.getPlayUrl().isEmpty() && ApiConfig.get().getFlags().contains(result.getFlag())) || result.getJx() == 1;
            mBinding.control.parseLayout.setVisibility(useParse ? View.VISIBLE : View.GONE);
            mPlayers.start(result, useParse);
            resetFocus(useParse);
        });
        mViewModel.result.observe(this, result -> {
            if (result.getList().isEmpty()) mBinding.progressLayout.showEmpty();
            else setDetail(result.getList().get(0));
            Notify.dismiss();
        });
        mViewModel.search.observe(this, result -> {
            setSearch(result.getList());
        });
    }

    private void resetFocus(boolean useParse) {
        findViewById(R.id.timeBar).setNextFocusUpId(useParse ? R.id.parse : R.id.next);
        for (int i = 0; i < mBinding.control.actionLayout.getChildCount(); i++) {
            mBinding.control.actionLayout.getChildAt(i).setNextFocusDownId(useParse ? R.id.parse : R.id.timeBar);
        }
    }

    private void getDetail() {
        mViewModel.detailContent(getKey(), getId());
    }

    private void getDetail(Vod item) {
        getIntent().putExtra("key", item.getSite().getKey());
        getIntent().putExtra("id", item.getVodId());
        mBinding.scroll.scrollTo(0, 0);
        Clock.get().setCallback(null);
        Notify.progress(this);
        mPlayers.stop();
        hideProgress();
        getDetail();
    }

    private void getPlayer(boolean replay) {
        Vod.Flag.Episode item = (Vod.Flag.Episode) mEpisodeAdapter.get(getEpisodePosition());
        if (mFullscreen && mPlayers.getRetry() == 0) Notify.show(ResUtil.getString(R.string.play_ready, item.getName()));
        mBinding.widget.title.setText(getString(R.string.detail_title, mBinding.name.getText(), item.getName()));
        mViewModel.playerContent(getKey(), getVodFlag().getFlag(), item.getUrl());
        Clock.get().setCallback(null);
        updateHistory(item, replay);
        showProgress();
        hideError();
    }

    private void setDetail(Vod item) {
        mBinding.progressLayout.showContent();
        mBinding.video.setTag(item.getVodPic());
        mBinding.name.setText(item.getVodName());
        setText(mBinding.remark, 0, item.getVodRemarks());
        setText(mBinding.year, R.string.detail_year, item.getVodYear());
        setText(mBinding.area, R.string.detail_area, item.getVodArea());
        setText(mBinding.type, R.string.detail_type, item.getTypeName());
        setText(mBinding.site, R.string.detail_site, getSite().getName());
        setText(mBinding.actor, R.string.detail_actor, Html.fromHtml(item.getVodActor()).toString());
        setText(mBinding.content, R.string.detail_content, Html.fromHtml(item.getVodContent()).toString());
        setText(mBinding.director, R.string.detail_director, Html.fromHtml(item.getVodDirector()).toString());
        mFlagAdapter.setItems(item.getVodFlags(), null);
        mBinding.content.setMaxLines(getMaxLines());
        mBinding.video.requestFocus();
        if (hasFlag()) checkHistory();
        getPart(item.getVodName());
        checkKeep();
    }

    private int getMaxLines() {
        int lines = 1;
        if (isGone(mBinding.actor)) ++lines;
        if (isGone(mBinding.remark)) ++lines;
        if (isGone(mBinding.director)) ++lines;
        return lines;
    }

    private void setText(TextView view, int resId, String text) {
        view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        view.setText(resId > 0 ? ResUtil.getString(resId, text) : text);
        view.setTag(text);
    }

    private void setFlagActivated(Vod.Flag item) {
        if (mFlagAdapter.size() == 0 || item.isActivated()) return;
        for (int i = 0; i < mFlagAdapter.size(); i++) ((Vod.Flag) mFlagAdapter.get(i)).setActivated(item);
        mBinding.flag.setSelectedPosition(mFlagAdapter.indexOf(item));
        mEpisodeAdapter.setItems(item.getEpisodes(), null);
        notifyItemChanged(mBinding.flag, mFlagAdapter);
        setEpisodeAdapter(item.getEpisodes());
        seamless(item);
    }

    private void setEpisodeAdapter(List<Vod.Flag.Episode> items) {
        mBinding.episode.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        mEpisodeAdapter.setItems(items, null);
        setArray(items.size());
    }

    private void seamless(Vod.Flag flag) {
        Vod.Flag.Episode episode = flag.find(mHistory.getVodRemarks());
        if (episode == null || episode.isActivated()) return;
        mHistory.setVodRemarks(episode.getName());
        setEpisodeActivated(episode);
    }

    private void setEpisodeActivated(Vod.Flag.Episode item) {
        if (shouldEnterFullscreen(item)) return;
        mCurrent = mBinding.flag.getSelectedPosition();
        for (int i = 0; i < mFlagAdapter.size(); i++) ((Vod.Flag) mFlagAdapter.get(i)).toggle(mCurrent == i, item);
        mBinding.episode.setSelectedPosition(getEpisodePosition());
        notifyItemChanged(mBinding.episode, mEpisodeAdapter);
        if (mEpisodeAdapter.size() == 0) return;
        getPlayer(false);
    }

    private void reverseEpisode() {
        for (int i = 0; i < mFlagAdapter.size(); i++) Collections.reverse(((Vod.Flag) mFlagAdapter.get(i)).getEpisodes());
        setEpisodeAdapter(getVodFlag().getEpisodes());
        mBinding.episode.setSelectedPosition(getEpisodePosition());
    }

    private void setParseActivated(Parse item) {
        ApiConfig.get().setParse(item);
        Result result = mViewModel.getPlayer().getValue();
        if (result != null) mPlayers.start(result, true);
        notifyItemChanged(mBinding.control.parse, mParseAdapter);
        showProgress();
        hideError();
    }

    private void setArray(int size) {
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.play_reverse));
        items.add(getString(mHistory.getRevPlayText()));
        mEpisodePresenter.setNextFocusDown(size > 1 ? R.id.array : R.id.part);
        mPartPresenter.setNextFocusUp(size > 1 ? R.id.array : R.id.episode);
        mBinding.array.setVisibility(size > 1 ? View.VISIBLE : View.GONE);
        if (mHistory.isRevSort()) for (int i = size + 1; i > 0; i -= 20) items.add((i - 1) + "-" + Math.max(i - 20, 1));
        else for (int i = 0; i < size; i += 20) items.add((i + 1) + "-" + Math.min(i + 20, size));
        mArrayAdapter.setItems(items, null);
    }

    private void stopSearch() {
        if (mExecutor != null) mExecutor.shutdownNow();
        mSearchAdapter.clear();
    }

    private void initSearch(String keyword) {
        stopSearch();
        startSearch(keyword);
        mBinding.part.setTag(keyword);
    }

    private void startSearch(String keyword) {
        mExecutor = Executors.newFixedThreadPool(5);
        for (Site site : ApiConfig.get().getSites()) if (site.isSearchable() && !site.getKey().equals(getKey())) mExecutor.execute(() -> search(site, keyword));
    }

    private void search(Site site, String keyword) {
        try {
            mViewModel.searchContent(site, keyword);
        } catch (Throwable ignored) {
        }
    }

    private void setSearch(List<Vod> items) {
        Iterator<Vod> iterator = items.iterator();
        while (iterator.hasNext()) if (mismatch(iterator.next())) iterator.remove();
        mSearchAdapter.addAll(mSearchAdapter.size(), items);
        mBinding.search.setVisibility(View.VISIBLE);
    }

    private boolean mismatch(Vod item) {
        String name = mBinding.name.getText().toString();
        String keyword = mBinding.part.getTag().toString();
        boolean accurate = keyword.equals(name) && isVisible(mBinding.widget.error);
        return accurate && !item.getVodName().equals(keyword) || !item.getVodName().contains(keyword);
    }

    @Override
    public void onRevSort() {
        mHistory.setRevSort(!mHistory.isRevSort());
        reverseEpisode();
    }

    @Override
    public void onRevPlay(TextView view) {
        mHistory.setRevPlay(!mHistory.isRevPlay());
        view.setText(mHistory.getRevPlayText());
        Notify.show(mHistory.getRevPlayHint());
    }

    private boolean shouldEnterFullscreen(Vod.Flag.Episode item) {
        boolean enter = !mFullscreen && item.isActivated();
        if (enter) enterFullscreen();
        return enter;
    }

    private void enterFullscreen() {
        mBinding.video.setForeground(null);
        mBinding.video.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        mBinding.flag.setSelectedPosition(mCurrent);
        mFullscreen = true;
        onPlay(0);
    }

    private void exitFullscreen() {
        mBinding.video.setForeground(ResUtil.getDrawable(R.drawable.selector_video));
        mBinding.video.setLayoutParams(mFrameParams);
        mFullscreen = false;
        hideInfo();
    }

    private void onDesc() {
        String desc = mBinding.content.getTag().toString().trim();
        if (desc.length() > 0) DescDialog.show(this, desc);
    }

    private void onKeep() {
        Keep keep = Keep.find(getHistoryKey());
        Notify.show(keep != null ? R.string.keep_del : R.string.keep_add);
        if (keep != null) keep.delete();
        else createKeep();
        RefreshEvent.keep();
        checkKeep();
    }

    private void onVideo() {
        if (mFullscreen) onToggle();
        else enterFullscreen();
    }

    private void checkNext() {
        if (mHistory.isRevPlay()) onPrev();
        else onNext();
    }

    private void checkPrev() {
        if (mHistory.isRevPlay()) onNext();
        else onPrev();
    }

    private void onNext() {
        int current = getEpisodePosition();
        int max = mEpisodeAdapter.size() - 1;
        current = ++current > max ? max : current;
        Vod.Flag.Episode item = (Vod.Flag.Episode) mEpisodeAdapter.get(current);
        if (item.isActivated()) Notify.show(mHistory.isRevPlay() ? R.string.error_play_prev : R.string.error_play_next);
        else setEpisodeActivated(item);
    }

    private void onPrev() {
        int current = getEpisodePosition();
        current = --current < 0 ? 0 : current;
        Vod.Flag.Episode item = (Vod.Flag.Episode) mEpisodeAdapter.get(current);
        if (item.isActivated()) Notify.show(mHistory.isRevPlay() ? R.string.error_play_next : R.string.error_play_prev);
        else setEpisodeActivated(item);
    }

    private void onScale() {
        int index = mHistory.getScale();
        if (index == -1) index = Prefers.getVodScale();
        String[] array = ResUtil.getStringArray(R.array.select_scale);
        mHistory.setScale(index = index == array.length - 1 ? 0 : ++index);
        setScale(index);
    }

    private void onSpeed() {
        mBinding.control.speed.setText(mPlayers.addSpeed());
        mHistory.setSpeed(mPlayers.getSpeed());
    }

    private boolean onSpeedLong() {
        mBinding.control.speed.setText(mPlayers.toggleSpeed());
        mHistory.setSpeed(mPlayers.getSpeed());
        return true;
    }

    private void onOpening() {
        long current = mPlayers.getPosition();
        long duration = mPlayers.getDuration();
        if (current < 0 || current > duration / 2) return;
        mHistory.setOpening(current);
        mBinding.control.opening.setText(mPlayers.stringToTime(mHistory.getOpening()));
    }

    private boolean onOpeningReset() {
        mHistory.setOpening(0);
        mBinding.control.opening.setText(mPlayers.stringToTime(mHistory.getOpening()));
        return true;
    }

    private void onEnding() {
        long current = mPlayers.getPosition();
        long duration = mPlayers.getDuration();
        if (current < 0 || current < duration / 2) return;
        mHistory.setEnding(duration - current);
        mBinding.control.ending.setText(mPlayers.stringToTime(mHistory.getEnding()));
    }

    private boolean onEndingReset() {
        mHistory.setEnding(0);
        mBinding.control.ending.setText(mPlayers.stringToTime(mHistory.getEnding()));
        return true;
    }

    private void onPlayer() {
        mPlayers.stop();
        mPlayers.togglePlayer();
        mHistory.setPlayer(mPlayers.getPlayer());
        mBinding.control.tracks.setVisibility(View.GONE);
        getPlayer(false);
        setPlayerView();
    }

    private void onDecode() {
        mPlayers.toggleDecode();
        mPlayers.setupIjk(getIjk());
        mPlayers.setupExo(getExo());
        getPlayer(false);
        setDecodeView();
    }

    private void onTracks() {
        TrackSelectionDialog.createForPlayer(mPlayers.exo(), dialog -> {
        }).show(getSupportFragmentManager(), "tracks");
        hideControl();
    }

    private void onToggle() {
        if (isVisible(mBinding.control.getRoot())) hideControl();
        else showControl(mBinding.control.next);
    }

    private void showProgress() {
        mBinding.widget.progress.setVisibility(View.VISIBLE);
        App.post(mR3, 0);
    }

    private void hideProgress() {
        mBinding.widget.progress.setVisibility(View.GONE);
        App.removeCallbacks(mR3);
        Traffic.reset();
    }

    private void showError() {
        mBinding.widget.error.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        mBinding.widget.error.setVisibility(View.GONE);
    }

    private void showInfo() {
        mBinding.widget.center.setVisibility(View.VISIBLE);
        mBinding.widget.info.setVisibility(View.VISIBLE);
    }

    private void hideInfo() {
        mBinding.widget.center.setVisibility(View.GONE);
        mBinding.widget.info.setVisibility(View.GONE);
    }

    private void showControl(View view) {
        mBinding.control.getRoot().setVisibility(View.VISIBLE);
        view.requestFocus();
        setR1Callback();
    }

    private void hideControl() {
        mBinding.control.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mR1);
    }

    private void hideCenter() {
        mBinding.widget.action.setImageResource(R.drawable.ic_play);
        hideInfo();
    }

    private void setTraffic() {
        Traffic.setSpeed(mBinding.widget.traffic);
        App.post(mR3, 500);
    }

    private void setR1Callback() {
        App.removeCallbacks(mR1);
        App.post(mR1, 5000);
    }

    private void getPart(String source) {
        OkHttp.newCall("http://api.pullword.com/get.php?source=" + URLEncoder.encode(source.trim()) + "&param1=0&param2=0&json=1").enqueue(new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                List<String> items = Part.get(response.body().string());
                if (!items.contains(source)) items.add(0, source);
                App.post(() -> mPartAdapter.setItems(items, null));
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                List<String> items = List.of(source);
                App.post(() -> mPartAdapter.setItems(items, null));
            }
        });
    }

    private boolean hasFlag() {
        mBinding.flag.setVisibility(mFlagAdapter.size() > 0 ? View.VISIBLE : View.GONE);
        if (mFlagAdapter.size() == 0) Notify.show(R.string.error_episode);
        return mFlagAdapter.size() > 0;
    }

    private void checkHistory() {
        mHistory = History.find(getHistoryKey());
        mHistory = mHistory == null ? createHistory() : mHistory;
        setFlagActivated(mHistory.getFlag());
        if (mHistory.isRevSort()) reverseEpisode();
        if (mHistory.getScale() != -1) setScale(mHistory.getScale());
        mBinding.control.opening.setText(mPlayers.stringToTime(mHistory.getOpening()));
        mBinding.control.ending.setText(mPlayers.stringToTime(mHistory.getEnding()));
        mBinding.control.speed.setText(mPlayers.setSpeed(mHistory.getSpeed()));
        mPlayers.setPlayer(getPlayerType());
        setPlayerView();
        setDecodeView();
    }

    private History createHistory() {
        History history = new History();
        history.setKey(getHistoryKey());
        history.setCid(ApiConfig.getCid());
        history.setVodPic(mBinding.video.getTag().toString());
        history.setVodName(mBinding.name.getText().toString());
        history.findEpisode(mFlagAdapter);
        return history;
    }

    private void updateHistory(Vod.Flag.Episode item, boolean replay) {
        replay = replay || !item.equals(mHistory.getEpisode());
        long position = replay ? 0 : mHistory.getPosition();
        mHistory.setPosition(position);
        mHistory.setEpisodeUrl(item.getUrl());
        mHistory.setVodRemarks(item.getName());
        mHistory.setVodFlag(getVodFlag().getFlag());
        mHistory.setCreateTime(System.currentTimeMillis());
    }

    private void checkKeep() {
        mBinding.keep.setCompoundDrawablesRelativeWithIntrinsicBounds(Keep.find(getHistoryKey()) == null ? R.drawable.ic_keep_not_yet : R.drawable.ic_keep_added, 0, 0, 0);
    }

    private void createKeep() {
        Keep keep = new Keep();
        keep.setKey(getHistoryKey());
        keep.setCid(ApiConfig.getCid());
        keep.setSiteName(getSite().getName());
        keep.setVodPic(mBinding.video.getTag().toString());
        keep.setVodName(mBinding.name.getText().toString());
        keep.setCreateTime(System.currentTimeMillis());
        keep.save();
    }

    @Override
    public void onTimeChanged() {
        long current = mPlayers.getPosition();
        long duration = mPlayers.getDuration();
        if (current >= 0 && duration > 0) App.execute(() -> mHistory.update(current, duration));
        if (mHistory.getEnding() > 0 && duration > 0 && mHistory.getEnding() + current >= duration) {
            Clock.get().setCallback(null);
            checkNext();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerEvent(PlayerEvent event) {
        switch (event.getState()) {
            case 0:
                checkPosition();
                break;
            case Player.STATE_IDLE:
                break;
            case Player.STATE_BUFFERING:
                showProgress();
                break;
            case Player.STATE_READY:
                hideProgress();
                mPlayers.reset();
                mBinding.widget.size.setText(mPlayers.getSizeText());
                TrackSelectionDialog.setVisible(mPlayers.exo(), mBinding.control.tracks);
                break;
            case Player.STATE_ENDED:
                checkNext();
                break;
            default:
                if (!event.isRetry() || mPlayers.addRetry() > 3) onError(event.getMsg());
                else getPlayer(false);
                break;
        }
    }

    private void checkPosition() {
        mPlayers.seekTo(Math.max(mHistory.getOpening(), mHistory.getPosition()));
        Clock.get().setCallback(this);
    }

    private void onError(String msg) {
        int position = mBinding.flag.getSelectedPosition();
        if (position == mFlagAdapter.size() - 1) {
            initSearch(mBinding.name.getText().toString());
            mBinding.widget.text.setText(msg);
            Clock.get().setCallback(null);
            mPlayers.stop();
            hideProgress();
            showError();
        } else {
            mPlayers.reset();
            Vod.Flag flag = (Vod.Flag) mFlagAdapter.get(position + 1);
            Notify.show(ResUtil.getString(R.string.play_switching, flag.getFlag()));
            setFlagActivated(flag);
        }
    }

    private void onPause(boolean visible) {
        mBinding.widget.exoDuration.setText(mPlayers.getDurationTime());
        mBinding.widget.exoPosition.setText(mPlayers.getPositionTime(0));
        if (visible) showInfo();
        else hideInfo();
        mPlayers.pause();
    }

    private void onPlay(int delay) {
        App.post(mR2, delay);
        mPlayers.play();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mFullscreen && Utils.isMenuKey(event)) onToggle();
        if (isVisible(mBinding.control.getRoot())) setR1Callback();
        if (mFullscreen && isGone(mBinding.control.getRoot()) && mKeyDown.hasEvent(event)) return mKeyDown.onKeyDown(event);
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onSeeking(int time) {
        mBinding.widget.exoDuration.setText(mPlayers.getDurationTime());
        mBinding.widget.exoPosition.setText(mPlayers.getPositionTime(time));
        mBinding.widget.action.setImageResource(time > 0 ? R.drawable.ic_forward : R.drawable.ic_rewind);
        mBinding.widget.center.setVisibility(View.VISIBLE);
    }

    @Override
    public void onSeekTo(int time) {
        mPlayers.seekTo(time);
        mKeyDown.resetTime();
        onPlay(500);
    }

    @Override
    public void onKeyUp() {
        long current = mPlayers.getPosition();
        long half = mPlayers.getDuration() / 2;
        showControl(current < half ? mBinding.control.opening : mBinding.control.ending);
    }

    @Override
    public void onKeyDown() {
        showControl(mBinding.control.next);
    }

    @Override
    public void onKeyCenter() {
        if (mPlayers.isPlaying()) onPause(true);
        else onPlay(0);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        setResult(RESULT_OK);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Clock.start(mBinding.widget.time);
        onPlay(0);
    }

    @Override
    protected void onPause() {
        super.onPause();
        RefreshEvent.history();
        Clock.get().release();
        onPause(false);
    }

    @Override
    public void onBackPressed() {
        if (isVisible(mBinding.control.getRoot())) {
            hideControl();
        } else if (mFullscreen) {
            exitFullscreen();
        } else {
            stopSearch();
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPlayers.release();
        App.removeCallbacks(mR1, mR2, mR3);
    }
}
