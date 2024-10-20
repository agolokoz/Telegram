package org.telegram.ui.Components;

import android.content.*;
import android.graphics.*;
import android.graphics.Rect;
import android.graphics.drawable.*;
import android.view.*;

import androidx.annotation.*;
import androidx.core.content.*;
import androidx.recyclerview.widget.*;

import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Cells.*;

import java.util.*;

public abstract class ChatQuickReply {

    /**
     * TODO NotificationCenter.dialogsNeedReload
     * TODO check after first install, (item count could be less than 5)
     * TODO layout for top anchored cell
     */
    public static ActionBarPopupWindow show(@NonNull ChatActivity chatActivity, @NonNull ChatMessageCell cell, @NonNull Delegate delegate) {
        int rootHorizontalSpace = AndroidUtilities.dp(3);

        Adapter adapter = new Adapter();
        ReplyViewGroup rootLayout = new ReplyViewGroup(chatActivity.getContext(), chatActivity.getResourceProvider());
        rootLayout.setAdapter(adapter);

        int itemCount = 5 + 1;
        int windowWidth;
        do {
            --itemCount;
            windowWidth = rootLayout.getContentWidth(itemCount);
        } while (windowWidth + rootHorizontalSpace * 2 >= AndroidUtilities.displaySize.x);
        rootLayout.measure(View.MeasureSpec.makeMeasureSpec(windowWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

        ActionBarPopupWindow window = new ActionBarPopupWindow(rootLayout, windowWidth, LayoutHelper.WRAP_CONTENT) {
            @Override
            public void dismiss() {
                super.dismiss();
                delegate.onDismiss(this);
            }
        };
        window.setAnimationStyle(R.style.PopupContextAnimation);
        window.setClippingEnabled(true);
        window.setFocusable(true);
        window.setInputMethodMode(ActionBarPopupWindow.INPUT_METHOD_NOT_NEEDED);
        window.setOutsideTouchable(true);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);
        window.getContentView().setFocusableInTouchMode(true);

        int[] cellLocation = new int[2];
        cell.getLocationOnScreen(cellLocation);
        int xCell = cellLocation[0];
        int yCell = cellLocation[1];

        View anchorView = chatActivity.getChatListView();
        int x = Math.round(xCell + (windowWidth - ChatMessageCell.SIDE_BUTTON_SIZE) * 0.5f);
        if (x + rootHorizontalSpace * 2 + windowWidth > anchorView.getWidth()) {
            x = anchorView.getWidth() - rootHorizontalSpace * 2 - windowWidth;
        }
        int y = Math.round(yCell + cell.sideStartY + ChatMessageCell.SIDE_BUTTON_SIZE - rootLayout.getMeasuredHeight());
        if (y <= chatActivity.getActionBar().getHeight() + AndroidUtilities.dp(9)) {
            rootLayout.setListAboveReplyButton(false);
            y = Math.round(yCell + cell.sideStartY);
        }
        window.showAtLocation(anchorView, Gravity.NO_GRAVITY, x, y);

        return window;
    }

    private static class ReplyViewGroup extends ViewGroup {

        private static final int DECORATION_BOUND_SPACE = AndroidUtilities.dp(9);
        private static final int DECORATION_INNER_SPACE = AndroidUtilities.dp(5.5f);
        private static final int DECORATION_VERTICAL_SPACE = AndroidUtilities.dp(7);
        private static final int SHADOW_TOP_SPACE = AndroidUtilities.dp(2);
        private static final int SHADOW_SPACE = AndroidUtilities.dp(3);

        private final int listToButtonHeight = AndroidUtilities.dp(42);
        private final int listToNameHeight = AndroidUtilities.dp(30);
        private final RecyclerListView recyclerView;

        private boolean isListAboveReplyButton = true;

        public ReplyViewGroup(@NonNull Context context, @NonNull Theme.ResourcesProvider resourcesProvider) {
            super(context);
            setWillNotDraw(false);

            recyclerView = new RecyclerListView(getContext(), resourcesProvider) {

                private final Path clipPath = new Path();
                private final RectF clipRect = new RectF();
                private final Paint fillPaint = new Paint();

                {
                    fillPaint.setColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, resourcesProvider));
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
            recyclerView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
            recyclerView.setNestedScrollingEnabled(false);
            recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
            addView(recyclerView);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int widthSize = MeasureSpec.getSize(widthMeasureSpec);
            int listWidthSpec = MeasureSpec.makeMeasureSpec(widthSize - SHADOW_SPACE * 2, MeasureSpec.EXACTLY);
            int listHeightSpec = MeasureSpec.makeMeasureSpec(Adapter.ViewHolder.IMAGE_SIZE + DECORATION_VERTICAL_SPACE * 2, MeasureSpec.EXACTLY);
            recyclerView.measure(listWidthSpec, listHeightSpec);

            int targetHeight = listToButtonHeight + recyclerView.getMeasuredHeight() + listToNameHeight;
            setMeasuredDimension(widthSize, targetHeight);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int listTop = 0;
            if (isListAboveReplyButton) {
                listTop = listToNameHeight;
            } else {
                listTop = listToButtonHeight;
            }
            recyclerView.layout(SHADOW_SPACE, listTop, SHADOW_SPACE + recyclerView.getMeasuredWidth(), listTop + recyclerView.getMeasuredHeight());
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            super.dispatchDraw(canvas);
        }

        public void setAdapter(RecyclerListView.Adapter<?> adapter) {
            recyclerView.setAdapter(adapter);
        }

        public void setListAboveReplyButton(boolean isAbove) {
            isListAboveReplyButton = isAbove;
            requestLayout();
        }

        public int getContentWidth(int itemCount) {
            return ReplyViewGroup.SHADOW_SPACE * 2 + ReplyViewGroup.DECORATION_BOUND_SPACE * 2 +
                    Adapter.ViewHolder.IMAGE_SIZE * itemCount + ReplyViewGroup.DECORATION_INNER_SPACE * 2 * (itemCount - 1);
        }

        private RecyclerView.ItemDecoration getItemDecoration() {
            return new RecyclerView.ItemDecoration() {
                @Override
                public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                    if (parent.getAdapter() == null) {
                        return;
                    }
                    outRect.top = outRect.bottom = DECORATION_VERTICAL_SPACE;
                    outRect.left = outRect.right = DECORATION_INNER_SPACE;
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
    }


    private static class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

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
                    if (!(chat == null || ChatObject.isNotInChat(chat) || chat.gigagroup && !ChatObject.hasAdminRights(chat) || ChatObject.isChannel(chat) && !chat.creator && (chat.admin_rights == null || !chat.admin_rights.post_messages) && !chat.megagroup)) {
                        archivedDialogs.add(dialog);
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

            public static final int IMAGE_SIZE = AndroidUtilities.dp(42);

            private final AvatarDrawable avatarDrawable = new AvatarDrawable();
            private final BackupImageView imageView;
            private final int currentAccount = UserConfig.selectedAccount;

            public ViewHolder(@NonNull ViewGroup parent) {
                super(new BackupImageView(parent.getContext()));
                imageView = (BackupImageView) this.itemView;
                imageView.setBackground(avatarDrawable);
                imageView.setLayoutParams(new RecyclerView.LayoutParams(IMAGE_SIZE, IMAGE_SIZE));
                imageView.setRoundRadius(Math.round(IMAGE_SIZE * 0.5f));
            }

            public void bind(@NonNull TLRPC.Dialog dialog) {
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

        default void onDismiss(@NonNull ActionBarPopupWindow window) {}
    }
}
