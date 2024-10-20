package org.telegram.ui.Components;

import android.view.*;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.*;

import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Cells.*;

import java.util.*;

public abstract class ChatQuickReply {

    private static final int HORIZONTAL_SPACE = AndroidUtilities.dp(5);

    /**
     * TODO NotificationCenter.dialogsNeedReload
     */
    public static ActionBarPopupWindow show(@NonNull ChatActivity chatActivity, @NonNull ChatMessageCell cell, @NonNull Delegate delegate) {
        FrameLayout frameLayout = new FrameLayout(cell.getContext());

        Adapter adapter = new Adapter();

        RecyclerListView recyclerView = new RecyclerListView(chatActivity.getContext(), chatActivity.getResourceProvider());
        recyclerView.setAdapter(adapter);
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemAnimator(null);
        recyclerView.setLayoutManager(new LinearLayoutManager(chatActivity.getContext(), LinearLayoutManager.HORIZONTAL, false));
        recyclerView.setNestedScrollingEnabled(false);
        frameLayout.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, AndroidUtilities.dp(56)));

        ActionBarPopupWindow window = new ActionBarPopupWindow(frameLayout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT) {
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

        View anchorView = chatActivity.getChatListView();
        int x = Math.round(cell.sideStartX + (window.getWidth() - ChatMessageCell.SIDE_BUTTON_SIZE) * 0.5f);
        if (x + window.getWidth() > anchorView.getRight() + HORIZONTAL_SPACE) {
            x = anchorView.getRight() - HORIZONTAL_SPACE - window.getWidth();
        }
        int y = Math.round(cell.getTop() + cell.sideStartY - AndroidUtilities.dp(10) - window.getHeight());
        window.showAtLocation(anchorView, Gravity.LEFT | Gravity.TOP, x, y);

        return window;
    }

    public interface Delegate {

        default void onDismiss(@NonNull ActionBarPopupWindow window) {}
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
            long selfUserId = UserConfig.getInstance(UserConfig.selectedAccount).clientUserId;
            ArrayList<TLRPC.Dialog> allDialogs = MessagesController.getInstance(UserConfig.selectedAccount).getAllDialogs();
            dialogs.addAll(allDialogs);
            notifyDataSetChanged();
        }

        private static class ViewHolder extends RecyclerView.ViewHolder {

            public static final int IMAGE_SIZE = AndroidUtilities.dp(47);

            private final AvatarDrawable avatarDrawable = new AvatarDrawable();
            private final BackupImageView imageView;
            private final int currentAccount = UserConfig.selectedAccount;

            public ViewHolder(@NonNull ViewGroup parent) {
                super(new BackupImageView(parent.getContext()));
                imageView = (BackupImageView) this.itemView;
                imageView.setBackground(avatarDrawable);
                imageView.setLayoutParams(new RecyclerView.LayoutParams(IMAGE_SIZE, IMAGE_SIZE));
            }

            public void bind(@NonNull TLRPC.Dialog dialog) {
                if (DialogObject.isUserDialog(dialog.id)) {
                    TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialog.id);
                    avatarDrawable.setInfo(currentAccount, user);
                    imageView.setForUserOrChat(user, avatarDrawable);
                } else {
                    TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialog.id);
                    avatarDrawable.setInfo(currentAccount, chat);
                    imageView.setForUserOrChat(chat, avatarDrawable);
                }
            }
        }
    }
}
