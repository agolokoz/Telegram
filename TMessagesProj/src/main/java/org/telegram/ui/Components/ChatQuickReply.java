package org.telegram.ui.Components;

import static org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT;

import android.animation.*;
import android.annotation.*;
import android.content.*;
import android.graphics.*;
import android.graphics.Rect;
import android.text.*;
import android.view.*;
import android.view.animation.*;
import android.widget.*;

import androidx.annotation.*;
import androidx.core.math.*;
import androidx.recyclerview.widget.*;

import org.telegram.messenger.*;
import org.telegram.tgnet.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Cells.*;

import java.util.*;

public abstract class ChatQuickReply {

    /**
     * TODO NotificationCenter.dialogsNeedReload
     * TODO check after first install, (item count could be less than 5)
     */
    @SuppressLint("ClickableViewAccessibility")
    public static ReplyViewGroup show(@NonNull ChatActivity chatActivity, @NonNull ChatMessageCell cell, @NonNull Delegate delegate) {
        int rootHorizontalSpace = AndroidUtilities.dp(3);

        ReplyViewGroup rootLayout = new ReplyViewGroup(chatActivity);
        rootLayout.setAdapter(new Adapter());
        rootLayout.setDelegate(delegate);

        int itemCount = 5 + 1;
        int windowWidth;
        do {
            --itemCount;
            windowWidth = rootLayout.getContentWidth(itemCount);
        } while (windowWidth + rootHorizontalSpace * 2 >= AndroidUtilities.displaySize.x);
        rootLayout.measure(View.MeasureSpec.makeMeasureSpec(windowWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

        int[] cellLocation = new int[2];
        cell.getLocationOnScreen(cellLocation);
        int xCell = cellLocation[0];
        int yCell = cellLocation[1];

        View anchorView = chatActivity.getChatListView();
        int x = Math.round(xCell + (windowWidth - ChatMessageCell.SIDE_BUTTON_SIZE) * 0.5f);
        if (x + rootHorizontalSpace * 2 + windowWidth > anchorView.getWidth()) {
            x = anchorView.getWidth() - rootHorizontalSpace - windowWidth;
        }
        int y = Math.round(yCell + cell.sideStartY + ChatMessageCell.SIDE_BUTTON_SIZE - rootLayout.getMeasuredHeight());
        if (y <= chatActivity.getActionBar().getHeight() + AndroidUtilities.dp(9)) {
            rootLayout.setListAboveReplyButton(false);
            y = Math.round(yCell + cell.sideStartY);
        }

        chatActivity.getLayoutContainer().addView(rootLayout, windowWidth, WRAP_CONTENT);
        rootLayout.setTranslationX(x);
        rootLayout.setTranslationY(y);

        return rootLayout;
    }

    @SuppressLint("ViewConstructor")
    public static class ReplyViewGroup extends ViewGroup {

        private static final int DECORATION_BOUND_SPACE = AndroidUtilities.dp(3.5f);
        private static final int SHADOW_SPACE = AndroidUtilities.dp(3);
        private static final int NAME_BG_HORIZONTAL_OFFSET = AndroidUtilities.dp(7);
        private static final int NAME_BG_TOP_OFFSET = AndroidUtilities.dp(4);
        private static final int NAME_BG_BOTTOM_OFFSET = AndroidUtilities.dp(3);

        private final int listToButtonHeight = AndroidUtilities.dp(42);
        private final int listToNameHeight = AndroidUtilities.dp(30);
        private final List<NameState> nameStateList = new ArrayList<>();
        private final RectF textBackgroundRect = new RectF();
        private final BaseFragment fragment;
        private final Paint nameBackgroundPaint;
        private final TextPaint nameTextPaint;
        private final RecyclerListView recyclerView;

        @Nullable
        private Adapter adapter;
        @Nullable
        private Delegate delegate;
        @Nullable
        private ValueAnimator animator;
        @Nullable
        private ViewAnimationState[] viewStates;
        private boolean isListAboveReplyButton = true;
        private boolean isLongTapped = true;
        private int selectedChildViewPosition = -1;

        public ReplyViewGroup(@NonNull BaseFragment fragment) {
            super(fragment.getContext());
            this.fragment = fragment;
            setWillNotDraw(false);
            nameBackgroundPaint = getThemedPaint(Theme.key_paint_chatActionBackground);
            nameTextPaint = new TextPaint(getThemedPaint(Theme.key_paint_chatActionText));
            nameTextPaint.setTextSize(AndroidUtilities.dp(12f));

            recyclerView = new RecyclerListView(getContext(), fragment.getResourceProvider()) {

                private final Path clipPath = new Path();
                private final RectF clipRect = new RectF();
                private final Paint fillPaint = new Paint();

                private final GestureDetector.SimpleOnGestureListener gestureListener = new GestureDetector.SimpleOnGestureListener() {

                    @Override
                    public boolean onDown(@NonNull MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(@NonNull MotionEvent e) {
                        View view = findChildViewUnder(e.getX(), e.getY());
                        if (view != null) {
                            shareToDialog(view);
                        }
                        return super.onSingleTapUp(e);
                    }

                    @Override
                    public void onLongPress(@NonNull MotionEvent e) {
                        super.onLongPress(e);
                        isLongTapped = true;
                    }
                };
                private final GestureDetector gestureDetector = new GestureDetector(getContext(), gestureListener);

                {
                    fillPaint.setColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, resourcesProvider));
                }

                @Override
                public boolean onTouchEvent(MotionEvent e) {
                    boolean isGestureDetectorHandled = gestureDetector.onTouchEvent(e);
                    if (e.getAction() == MotionEvent.ACTION_MOVE) {
                        if (isLongTapped) {
                            View view = findChildViewUnder(e.getX(), e.getY());
                            onListChildSelected(indexOfChild(view));
                        }
                    } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL || e.getAction() == MotionEvent.ACTION_OUTSIDE) {
                        View view = getChildAt(selectedChildViewPosition);
                        if (view != null) {
                            shareToDialog(view);
                        }
                        onListChildSelected(-1);
                        isLongTapped = false;
                    }
                    return isGestureDetectorHandled || isLongTapped || super.onTouchEvent(e);
                }

                @Override
                protected void onMeasure(int widthSpec, int heightSpec) {
                    super.onMeasure(widthSpec, heightSpec);
                    if (clipRect.width() != getMeasuredWidth() || clipRect.height() != getMeasuredHeight()) {
                        clipRect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
                        clipPath.reset();
                        clipPath.addRoundRect(clipRect, clipRect.height() * 0.5f, clipRect.height() * 0.5f, Path.Direction.CW);
                    }
                }

                @Override
                protected void dispatchDraw(Canvas canvas) {
                    canvas.save();
                    canvas.clipPath(clipPath, Region.Op.INTERSECT);
                    canvas.drawRect(clipRect, fillPaint);
                    super.dispatchDraw(canvas);
                    canvas.restore();
                }
            };
            recyclerView.setHasFixedSize(true);
            recyclerView.setItemAnimator(null);
            recyclerView.addItemDecoration(getItemDecoration());
            recyclerView.setLayoutManager(new LinearLayoutManager(fragment.getContext(), LinearLayoutManager.HORIZONTAL, false));
            recyclerView.setNestedScrollingEnabled(false);
            recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
            addView(recyclerView);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int widthSize = MeasureSpec.getSize(widthMeasureSpec);
            int listWidthSpec = MeasureSpec.makeMeasureSpec(widthSize - SHADOW_SPACE * 2, MeasureSpec.EXACTLY);
            int listHeightSpec = MeasureSpec.makeMeasureSpec(Adapter.ViewHolder.IMAGE_LAYOUT_HEIGHT, MeasureSpec.EXACTLY);
            recyclerView.measure(listWidthSpec, listHeightSpec);

            int targetHeight = listToButtonHeight + recyclerView.getMeasuredHeight() +
                    (isListAboveReplyButton ? listToNameHeight : 0);
            setMeasuredDimension(widthSize, targetHeight);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int listTop;
            if (isListAboveReplyButton) {
                listTop = listToNameHeight;
            } else {
                listTop = listToButtonHeight;
            }
            recyclerView.layout(SHADOW_SPACE, listTop, SHADOW_SPACE + recyclerView.getMeasuredWidth(), listTop + recyclerView.getMeasuredHeight());
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            float topOffset = isListAboveReplyButton ? 0f : (listToButtonHeight - listToNameHeight);

            for (int i = 0; i < nameStateList.size(); ++i) {
                StaticLayout staticLayout = nameStateList.get(i).staticLayout;
                int alpha = Math.round(255 * nameStateList.get(i).currentProgress);

                textBackgroundRect.set(-NAME_BG_HORIZONTAL_OFFSET, -NAME_BG_TOP_OFFSET, staticLayout.getWidth() + NAME_BG_HORIZONTAL_OFFSET, staticLayout.getHeight() + NAME_BG_BOTTOM_OFFSET);

                canvas.save();
                canvas.translate(nameStateList.get(i).x, topOffset + NAME_BG_TOP_OFFSET);
                float scale = 0.8f + 0.2f * nameStateList.get(i).currentProgress;
                canvas.scale(scale, scale, textBackgroundRect.centerX(), textBackgroundRect.bottom + textBackgroundRect.height() * 0.5f);

                float radius = textBackgroundRect.height() * 0.5f;
                int savedBackgroundAlpha = nameBackgroundPaint.getAlpha();
                nameBackgroundPaint.setAlpha(Math.round(savedBackgroundAlpha / 255f * alpha));
                canvas.drawRoundRect(textBackgroundRect, radius, radius, nameBackgroundPaint);
                nameBackgroundPaint.setAlpha(savedBackgroundAlpha);

                int savedTextAlpha = staticLayout.getPaint().getAlpha();
                staticLayout.getPaint().setAlpha(alpha);
                nameStateList.get(i).staticLayout.draw(canvas);
                staticLayout.getPaint().setAlpha(savedTextAlpha);

                canvas.restore();
            }
        }

        public void setAdapter(@NonNull Adapter adapter) {
            this.adapter = adapter;
            recyclerView.setAdapter(adapter);
        }

        public void setDelegate(@Nullable Delegate delegate) {
            this.delegate = delegate;
        }

        public void setListAboveReplyButton(boolean isAbove) {
            isListAboveReplyButton = isAbove;
            requestLayout();
        }

        public int getContentWidth(int itemCount) {
            return ReplyViewGroup.SHADOW_SPACE * 2 + ReplyViewGroup.DECORATION_BOUND_SPACE * 2 +
                    Adapter.ViewHolder.IMAGE_LAYOUT_WIDTH * itemCount;
        }

        private void onListChildSelected(int position) {
            if (position == selectedChildViewPosition) {
                return;
            }

            if (animator != null) {
                animator.cancel();
            }

            selectedChildViewPosition = position;
            recyclerView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (viewStates == null) {
                viewStates = new ViewAnimationState[recyclerView.getChildCount()];
                for (int i = 0; i < viewStates.length; ++i) {
                    viewStates[i] = new ViewAnimationState(recyclerView.getChildAt(i));
                }
            }
            for (int i = 0; i < viewStates.length; ++i) {
                int targetState;
                if (position < 0) {
                    targetState = ViewAnimationState.STATE_DEFAULT;
                } else if (i == selectedChildViewPosition) {
                    targetState = ViewAnimationState.STATE_SELECTED;
                } else {
                    targetState = ViewAnimationState.STATE_DESELECTED;
                }
                viewStates[i].prepare(targetState);
            }

            View selectedView = recyclerView.getChildAt(position);
            TLRPC.Dialog dialog = null;
            if (selectedView != null && adapter != null) {
                int adapterPosition = recyclerView.getChildAdapterPosition(selectedView);
                dialog = adapter.getItemAt(adapterPosition);
            }
            boolean isDialogFound = false;
            for (int i = 0; i < nameStateList.size(); ++i) {
                if (dialog != null && nameStateList.get(i).dialogId == dialog.id) {
                    nameStateList.get(i).prepare(1.0f);
                    isDialogFound = true;
                } else {
                    nameStateList.get(i).prepare(0.0f);
                }
            }
            if (!isDialogFound && dialog != null) {
                String name = getDialogName(dialog);
                if (!TextUtils.isEmpty(name)) {
                    int width = (int) Math.ceil(nameTextPaint.measureText(name));
                    StaticLayout staticLayout = new StaticLayout(name, nameTextPaint, width, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
                    float x = (selectedView.getLeft() + selectedView.getWidth() * 0.5f) - width * 0.5f;
                    if (x - NAME_BG_HORIZONTAL_OFFSET < SHADOW_SPACE) {
                        x = SHADOW_SPACE + NAME_BG_HORIZONTAL_OFFSET;
                    } else if (x + NAME_BG_HORIZONTAL_OFFSET * 2 + width >= getWidth() - SHADOW_SPACE) {
                        x = getWidth() - SHADOW_SPACE - width - NAME_BG_HORIZONTAL_OFFSET * 2;
                    }
                    NameState state = new NameState(dialog.id, x, staticLayout);
                    state.prepare(1.0f);
                    nameStateList.add(state);
                }
            }

            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.addUpdateListener(animation -> {
                float progress = (float) animation.getAnimatedValue();
                for (int i = 0; i < viewStates.length; ++i) {
                    viewStates[i].apply(progress);
                }
                for (int i = 0; i < nameStateList.size(); ++i) {
                    nameStateList.get(i).apply(progress);
                }
                invalidate();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    if (position < 0) {
                        viewStates = null;
                    }
                    for (int i = nameStateList.size() - 1; i >= 0; --i) {
                        if (nameStateList.get(i).currentProgress == 0.0f) {
                            nameStateList.remove(i);
                        }
                    }
                }
            });
            animator.setDuration(150L);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            animator.start();
        }

        public void passTouchEvent(MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_CANCEL) {
                return;
            }
            if (ev.getAction() == MotionEvent.ACTION_UP) {
                shareToDialog(recyclerView.getChildAt(selectedChildViewPosition));
                isLongTapped = false;
                animateClose();
                return;
            } else {
                isLongTapped = true;
            }
            MotionEvent newEvent = MotionEvent.obtain(ev);
            newEvent.offsetLocation(-recyclerView.getLeft(), -recyclerView.getTop());
            recyclerView.onTouchEvent(newEvent);
        }

        private void animateClose() {
            animate().alpha(0.0f).scaleX(0.8f).scaleY(0.8f).setDuration(125)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            super.onAnimationEnd(animation);
                            ViewParent parent = getParent();
                            if (parent != null) {
                                ((ViewGroup) parent).removeView(ReplyViewGroup.this);
                            }
                            if (delegate != null) {
                                delegate.onClose();
                            }
                        }
                    })
                    .start();
        }

        private void shareToDialog(View view) {
            int position = recyclerView.getChildAdapterPosition(view);
            TLRPC.Dialog dialog = adapter.getItemAt(position);
            if (dialog == null) {
                return;
            }

            Bulletin bulletin = BulletinFactory.createForwardedBulletin(fragment.getContext(), fragment.getLayoutContainer(), 1, dialog.id, 1, fragment.getThemedColor(Theme.key_undo_background), fragment.getThemedColor(Theme.key_undo_infoColor));
            Bulletin.Layout bulletinLayout = bulletin.getLayout();
            bulletin.show();

            if (bulletinLayout instanceof Bulletin.LottieLayout) {
                Bulletin.LottieLayout lottieLayout = (Bulletin.LottieLayout) bulletinLayout;
                lottieLayout.isAutoPlay = false;
                bulletinLayout.addCallback(getBulletinCallback(((ViewGroup) view).getChildAt(0)));
            }
        }

        private Bulletin.Layout.Callback getBulletinCallback(View sharedView) {
            return new Bulletin.Layout.Callback() {

                @Override
                public void onEnterTransitionStart(@NonNull Bulletin.Layout layout) {
                    Bulletin.Layout.Callback.super.onEnterTransitionStart(layout);
                    Bulletin.LottieLayout lottieLayout = ((Bulletin.LottieLayout) layout);

                    final int[] imageLocation = new int[2];
                    lottieLayout.imageView.getLocationOnScreen(imageLocation);
                    lottieLayout.imageView.setScaleX(0f);
                    lottieLayout.imageView.setScaleY(0f);

                    final int[] viewLocation = new int[2];
                    sharedView.getLocationOnScreen(viewLocation);

                    final int sharedViewSize = Math.round(sharedView.getWidth() * sharedView.getScaleX());
                    final float xTranslationDiff = (imageLocation[0] + lottieLayout.imageView.getWidth() * 0.5f) - (viewLocation[0] + sharedViewSize * 0.5f);
                    final float yTranslationDiff = (imageLocation[1] - lottieLayout.getTranslationY() + lottieLayout.imageView.getHeight() * 0.5f) - (viewLocation[1] + sharedViewSize * 0.5f);
                    final float targetScale = AndroidUtilities.dpf2(27) / sharedViewSize;

                    ViewParent parent = sharedView.getParent();
                    if (parent != null) {
                        ((ViewGroup) parent).removeView(sharedView);
                    }
                    fragment.getLayoutContainer().addView(sharedView, sharedViewSize, sharedViewSize);
                    sharedView.setTranslationX(viewLocation[0]);
                    sharedView.setTranslationY(viewLocation[1]);

                    int alphaTranslation = Math.round(sharedViewSize * targetScale * 0.5f);
                    ValueAnimator moveViewAnimator = ValueAnimator.ofFloat(0f, 1f);
                    moveViewAnimator.addUpdateListener(animation -> {
                        float progress = (float) animation.getAnimatedValue();
                        float scale = targetScale + (1f - targetScale) * (1f - progress);
                        sharedView.setScaleX(scale);
                        sharedView.setScaleY(scale);

                        float yTrans = yTranslationDiff * getProgressValue(progress);
                        sharedView.setTranslationX(viewLocation[0] + xTranslationDiff * progress);
                        sharedView.setTranslationY(viewLocation[1] + yTrans);

                        if (yTranslationDiff - yTrans < alphaTranslation) {
                            float alphaProgress = (yTranslationDiff - yTrans) / alphaTranslation;
                            sharedView.setAlpha(alphaProgress);
                            if (!lottieLayout.imageView.isPlaying()) {
                                lottieLayout.imageView.playAnimation();
                            }
                            lottieLayout.imageView.setScaleX(1f - alphaProgress);
                            lottieLayout.imageView.setScaleY(1f - alphaProgress);
                        }
                    });
                    moveViewAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
                    moveViewAnimator.setDuration(Bulletin.Layout.DefaultTransition.DefaultDuration);
                    moveViewAnimator.start();
                }

                private float getProgressValue(float progress) {
                    return progress * progress * progress * progress * progress;
                }
            };
        }

        private RecyclerView.ItemDecoration getItemDecoration() {
            return new RecyclerView.ItemDecoration() {
                @Override
                public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                    if (parent.getAdapter() == null) {
                        return;
                    }
                    int position = parent.getChildAdapterPosition(view);
                    if (position == 0) {
                        if (LocaleController.isRTL) {
                            outRect.right = DECORATION_BOUND_SPACE;
                        } else {
                            outRect.left = DECORATION_BOUND_SPACE;
                        }
                    }
                    if (position == parent.getAdapter().getItemCount() - 1) {
                        if (LocaleController.isRTL) {
                            outRect.left = DECORATION_BOUND_SPACE;
                        } else {
                            outRect.right = DECORATION_BOUND_SPACE;
                        }
                    }
                }
            };
        }

        @Nullable
        private String getDialogName(TLRPC.Dialog dialog) {
            int currentAccount = UserConfig.selectedAccount;
            TLObject object = DialogObject.isUserDialog(dialog.id)
                    ? MessagesController.getInstance(currentAccount).getUser(dialog.id)
                    : MessagesController.getInstance(currentAccount).getChat(-dialog.id);
            return object == null ? null : DialogObject.getDialogTitle(object);
        }

        private Paint getThemedPaint(String paintKey) {
            Paint paint = fragment.getResourceProvider() != null ? fragment.getResourceProvider().getPaint(paintKey) : null;
            return paint != null ? paint : Theme.getThemePaint(paintKey);
        }

        private static class ViewAnimationState {

            public static final int STATE_DEFAULT = 0;
            public static final int STATE_SELECTED = 1;
            public static final int STATE_DESELECTED = 2;

            @NonNull
            public final View view;
            private float startScale = 1f;
            private float startAlpha = 1f;
            private float targetScale = 1f;
            private float targetAlpha = 1f;

            public ViewAnimationState(@NonNull View view) {
                this.view = view;
            }

            public void prepare(int targetState) {
                startAlpha = view.getAlpha();
                startScale = view.getScaleX();
                if (targetState == STATE_DEFAULT) {
                    targetAlpha = 1.0f;
                    targetScale = 1.0f;
                } else if (targetState == STATE_SELECTED) {
                    targetAlpha = 1.0f;
                    targetScale = 1.1f;
                } else if (targetState == STATE_DESELECTED) {
                    targetAlpha = 0.5f;
                    targetScale = 1.0f;
                }
            }

            public void apply(float progress) {
                view.setAlpha(AndroidUtilities.lerp(startAlpha, targetAlpha, progress));
                float scale = AndroidUtilities.lerp(startScale, targetScale, progress);
                view.setScaleX(scale);
                view.setScaleY(scale);
            }
        }
    }

    private static class NameState {

        public final long dialogId;
        public final float x;
        public final StaticLayout staticLayout;

        public float currentProgress = 0.0f;
        private float startProgress = 0.0f;
        private float targetProgress = 0.0f;

        public NameState(long id, float x, StaticLayout staticLayout) {
            this.dialogId = id;
            this.x = x;
            this.staticLayout = staticLayout;
        }

        public void prepare(float targetProgress) {
            this.startProgress = currentProgress;
            this.targetProgress = targetProgress;
        }

        public void apply(float progress) {
            currentProgress = AndroidUtilities.lerp(startProgress, targetProgress, progress);
        }
    }

    public static class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final List<TLRPC.Dialog> dialogs = new ArrayList<>();

        public Adapter() {
            fetchDialogs();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(parent);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof ViewHolder) {
                ((ViewHolder) holder).bind(dialogs.get(position));
            }
        }

        @Override
        public int getItemCount() {
            return dialogs.size();
        }

        @Nullable
        public TLRPC.Dialog getItemAt(int position) {
            if (position < 0 || dialogs.size() <= position) {
                return null;
            }
            return dialogs.get(position);
        }

        public void fetchDialogs() {
            int currentAccount = UserConfig.selectedAccount;
            long selfUserId = UserConfig.getInstance(currentAccount).clientUserId;
            ArrayList<TLRPC.Dialog> allDialogs = MessagesController.getInstance(currentAccount).getAllDialogs();
            ArrayList<TLRPC.Dialog> archivedDialogs = new ArrayList<>();
            TLRPC.Dialog selfDialog = null;

            if (!MessagesController.getInstance(currentAccount).dialogsForward.isEmpty()) {
                TLRPC.Dialog dialog = MessagesController.getInstance(currentAccount).dialogsForward.get(0);
                allDialogs.add(0, dialog);
            }

            for (int i = allDialogs.size() - 1; i >= 0; --i) {
                TLRPC.Dialog dialog = allDialogs.get(i);
                if (!(dialog instanceof TLRPC.TL_dialog) || DialogObject.isEncryptedDialog(dialog.id)) {
                    allDialogs.remove(i);
                }
                if (dialog.id == selfUserId) {
                    selfDialog = dialog;
                    allDialogs.remove(i);
                }
                if (DialogObject.isUserDialog(dialog.id)) {
                    if (dialog.folder_id == 1) {
                        archivedDialogs.add(dialog);
                        allDialogs.remove(i);
                    }
                } else {
                    TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialog.id);
                    if (chat == null || !ChatObject.canSendMessages(chat)) {
                        allDialogs.remove(i);
                    }
                }
            }
            if (selfDialog != null) {
                dialogs.add(selfDialog);
            }
            dialogs.addAll(allDialogs);
            dialogs.addAll(archivedDialogs);
            notifyDataSetChanged();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {

            public static final int IMAGE_LAYOUT_WIDTH = AndroidUtilities.dp(53);
            public static final int IMAGE_LAYOUT_HEIGHT = AndroidUtilities.dp(56);

            private final AvatarDrawable avatarDrawable = new AvatarDrawable();
            private final BackupImageView imageView;
            private final int currentAccount = UserConfig.selectedAccount;
            @Nullable
            private TLRPC.Dialog dialog;

            public ViewHolder(@NonNull ViewGroup parent) {
                super(new FrameLayout(parent.getContext()));

                imageView = new BackupImageView(parent.getContext());
                imageView.setBackground(avatarDrawable);
                imageView.setRoundRadius(Math.round(AndroidUtilities.dp(42) * 0.5f));

                FrameLayout frameLayout = (FrameLayout) itemView;
                frameLayout.addView(imageView, LayoutHelper.createFrame(42, 42, Gravity.CENTER));
                frameLayout.setLayoutParams(new RecyclerView.LayoutParams(IMAGE_LAYOUT_WIDTH, IMAGE_LAYOUT_HEIGHT));
            }

            public void bind(@NonNull TLRPC.Dialog dialog) {
                this.dialog = dialog;
                if (DialogObject.isUserDialog(dialog.id)) {
                    TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialog.id);
                    if (UserObject.isUserSelf(user)) {
                        avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED);
                        imageView.setImage(null, null, avatarDrawable, user);
                    } else {
                        avatarDrawable.setInfo(currentAccount, user);
                        imageView.setForUserOrChat(user, avatarDrawable);
                    }
                } else {
                    TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialog.id);
                    avatarDrawable.setInfo(currentAccount, chat);
                    imageView.setForUserOrChat(chat, avatarDrawable);
                }
            }
        }
    }

    public interface Delegate {

        default void onClose() {}
    }
}
