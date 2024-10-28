package org.telegram.ui.Components;

import static org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT;

import static java.lang.Math.cos;
import static java.lang.Math.sin;

import android.animation.*;
import android.annotation.*;
import android.graphics.*;
import android.graphics.Rect;
import android.graphics.drawable.*;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.view.animation.*;
import android.view.animation.Interpolator;
import android.widget.*;

import androidx.annotation.*;
import androidx.core.content.*;
import androidx.core.graphics.*;
import androidx.core.math.MathUtils;
import androidx.core.view.animation.*;
import androidx.recyclerview.widget.*;

import org.telegram.messenger.*;
import org.telegram.tgnet.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Cells.*;

import java.util.*;

public abstract class ChatQuickReply {

    private static final int DEFAULT_ITEM_COUNT = 5;

    @SuppressLint("ClickableViewAccessibility")
    public static ReplyViewGroup show(@NonNull ChatActivity chatActivity, @NonNull ChatMessageCell cell, @NonNull Delegate delegate) {
        int rootHorizontalSpace = AndroidUtilities.dp(3);

        Adapter adapter = new Adapter();
        ReplyViewGroup rootLayout = new ReplyViewGroup(chatActivity, cell);
        rootLayout.setAdapter(adapter);
        rootLayout.setDelegate(delegate);
        rootLayout.setClipChildren(false);
        rootLayout.setClipToPadding(false);

        int itemCount = Math.min(DEFAULT_ITEM_COUNT, adapter.getItemCount()) + 1;
        int windowWidth;
        do {
            --itemCount;
            windowWidth = rootLayout.getContentWidth(itemCount);
        } while (windowWidth + rootHorizontalSpace * 2 >= AndroidUtilities.displaySize.x);
        rootLayout.measure(View.MeasureSpec.makeMeasureSpec(windowWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

        int[] cellLocation = new int[2];
        cell.getLocationOnScreen(cellLocation);

        View anchorView = chatActivity.getChatListView();
        int x = Math.round(cellLocation[0] + cell.sideStartX + ChatMessageCell.SIDE_BUTTON_SIZE * 0.5f - windowWidth * 0.5f);
        if (x + rootHorizontalSpace * 2 + windowWidth > anchorView.getWidth()) {
            x = anchorView.getWidth() - rootHorizontalSpace - windowWidth;
        } else if (x < rootHorizontalSpace) {
            x = rootHorizontalSpace;
        }
        int y = Math.round(cellLocation[1] + cell.sideStartY + ChatMessageCell.SIDE_BUTTON_SIZE - rootLayout.getMeasuredHeight());
        if (y <= chatActivity.getActionBar().getHeight() + AndroidUtilities.dp(9)) {
            rootLayout.setListAboveReplyButton(false);
            y = Math.round(cellLocation[1] + cell.sideStartY);
        }
        rootLayout.setTranslationX(x);
        rootLayout.setTranslationY(y);
        rootLayout.setVisibility(View.INVISIBLE);

        chatActivity.getLayoutContainer().addView(rootLayout, windowWidth, WRAP_CONTENT);
        RectF sideButtonRect = new RectF(cellLocation[0] + cell.sideStartX - x, cellLocation[1] + cell.sideStartY - y, 0, 0);
        sideButtonRect.right = sideButtonRect.left + ChatMessageCell.SIDE_BUTTON_SIZE;
        sideButtonRect.bottom = sideButtonRect.top + ChatMessageCell.SIDE_BUTTON_SIZE;
        AndroidUtilities.runOnUIThread(() -> rootLayout.openAnimation(sideButtonRect), 64);

        return rootLayout;
    }

    @SuppressLint("ViewConstructor")
    public static class ReplyViewGroup extends ViewGroup {

        private static final int DECORATION_BOUND_SPACE = AndroidUtilities.dp(3.5f);
        private static final int SHADOW_SPACE = AndroidUtilities.dp(3);
        private static final int NAME_BG_HORIZONTAL_OFFSET = AndroidUtilities.dp(7);
        private static final int NAME_BG_TOP_OFFSET = AndroidUtilities.dp(4);
        private static final int NAME_BG_BOTTOM_OFFSET = AndroidUtilities.dp(3);
        private static final float BLUR_DOWNSCALE = 8.0f;

        private final RectF bubbleRect = new RectF();
        private final RectF circleRect = new RectF();
        private final Path bubblePath = new Path();
        private final Matrix bubbleGradientMatrix = new Matrix();
        private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint blurredBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Matrix blurredBitmapMatrix = new Matrix();
        private final Drawable shadowDrawable = ContextCompat.getDrawable(getContext(), R.drawable.reactions_bubble_shadow);
        private final List<NameState> nameStateList = new ArrayList<>();
        private final RectF textBackgroundRect = new RectF();
        private final int listToButtonHeight = AndroidUtilities.dp(42);
        private final int listToNameHeight = AndroidUtilities.dp(30);
        private final ChatActivity fragment;
        private final ChatMessageCell cell;
        private final Drawable circleArrowDrawable;
        private final Paint nameBackgroundPaint;
        private final TextPaint nameTextPaint;
        private final RecyclerListView recyclerView;

        @Nullable
        private Adapter adapter;
        @Nullable
        private Delegate delegate;
        @Nullable
        private ValueAnimator dialogSelectedAnimator;
        @Nullable
        private AnimatorSet openAnimator;
        @Nullable
        private ViewAnimationState[] viewStates;
        @Nullable
        private Bitmap blurredBitmap;
        private boolean isListAboveReplyButton = true;
        private boolean isLongTapped = true;
        private boolean prevClipChildren = false;
        private int selectedChildViewPosition = -1;
        private int prevWidth = 0;
        private int prevHeight = 0;
        private int prevDrawSideButton = 0;
        private float circleRotationDegrees = 0;
        private boolean isOpenAnimationFinished = false;
        private boolean isDrawToBitmapCanvas = false;

        public ReplyViewGroup(@NonNull ChatActivity fragment, @NonNull ChatMessageCell cell) {
            super(fragment.getContext());
            this.fragment = fragment;
            this.cell = cell;
            setWillNotDraw(false);

            nameBackgroundPaint = new Paint(getThemedPaint(Theme.key_paint_chatActionBackground));
            nameTextPaint = new TextPaint(getThemedPaint(Theme.key_paint_chatActionText));
            nameTextPaint.setTextSize(AndroidUtilities.dp(12f));
            blurredBitmapMatrix.setScale(BLUR_DOWNSCALE, BLUR_DOWNSCALE);

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
                    fillPaint.setColor(fragment.getThemedColor(Theme.key_actionBarDefaultSubmenuBackground));
                }

                @SuppressLint("ClickableViewAccessibility")
                @Override
                public boolean onTouchEvent(MotionEvent e) {
                    if (!isOpenAnimationFinished) {
                        return true;
                    }
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

            circleArrowDrawable = fragment.getThemedDrawable(Theme.key_drawable_shareIcon);
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

        @SuppressLint("DrawAllocation")
        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left;
            int height = bottom - top;

            int listTop;
            if (isListAboveReplyButton) {
                listTop = listToNameHeight;
            } else {
                listTop = listToButtonHeight;
            }
            recyclerView.layout(SHADOW_SPACE, listTop, width - SHADOW_SPACE, listTop + recyclerView.getMeasuredHeight());

            if (prevWidth != width || prevHeight != height) {
                int backgroundColor = fragment.getThemedColor(Theme.key_actionBarDefaultSubmenuBackground);
                int sideButtonColor = Theme.serviceBitmap != null ? Theme.serviceBitmap.getPixel(
                        Math.round((cell.getX() + cell.sideStartX) * (Theme.serviceBitmap.getWidth() * 1.0f / fragment.getChatListView().getWidth())),
                        Math.round((cell.getY() + cell.sideStartY) * (Theme.serviceBitmap.getHeight() * 1.0f / fragment.getChatListView().getHeight()))
                ) : Color.TRANSPARENT;
                sideButtonColor = ColorUtils.compositeColors(fragment.getThemedColor(Theme.key_chat_serviceBackground), sideButtonColor);

                LinearGradient gradient;
                if (isListAboveReplyButton) {
                    int gradientHeight = height - recyclerView.getTop() + AndroidUtilities.dp(1);
                    float backgroundPart = (float) recyclerView.getHeight() / gradientHeight;
                    gradient = new LinearGradient(
                            0f, 0f, 0f, gradientHeight,
                            new int[]{backgroundColor, backgroundColor, sideButtonColor, sideButtonColor},
                            new float[]{0.0f, backgroundPart, backgroundPart + AndroidUtilities.dpf2(10) / height, 1.0f}, Shader.TileMode.CLAMP
                    );
                } else {
                    float backgroundPart = (float) recyclerView.getHeight() / height;
                    gradient = new LinearGradient(
                            0f, 0f, 0f, height,
                            new int[]{sideButtonColor, sideButtonColor, backgroundColor, backgroundColor},
                            new float[]{0.0f, 1 - backgroundPart - AndroidUtilities.dpf2(10) / height, 1f - backgroundPart, 1.0f}, Shader.TileMode.CLAMP
                    );
                }
                bubblePaint.setShader(gradient);
            }
            prevWidth = width;
            prevHeight = height;
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            if (!isDrawToBitmapCanvas && blurredBitmap != null) {
                canvas.drawBitmap(blurredBitmap, blurredBitmapMatrix, blurredBitmapPaint);
                return;
            }

            shadowDrawable.setBounds((int) bubbleRect.left - SHADOW_SPACE, (int) bubbleRect.top - SHADOW_SPACE, (int) bubbleRect.right + SHADOW_SPACE, (int) bubbleRect.bottom + SHADOW_SPACE);
            shadowDrawable.setAlpha(bubblePaint.getAlpha());
            shadowDrawable.draw(canvas);

            canvas.drawPath(bubblePath, bubblePaint);

            BaseCell.setDrawableBounds(circleArrowDrawable, circleRect.left + AndroidUtilities.dp(4), circleRect.top + AndroidUtilities.dp(4));
            canvas.save();
            canvas.rotate(circleRotationDegrees, circleArrowDrawable.getBounds().centerX(), circleArrowDrawable.getBounds().centerY());
            circleArrowDrawable.draw(canvas);
            canvas.restore();

            super.dispatchDraw(canvas);

            float topOffset = isListAboveReplyButton ? 0f : (listToButtonHeight - listToNameHeight);
            for (int i = 0; i < nameStateList.size(); ++i) {
                StaticLayout staticLayout = nameStateList.get(i).staticLayout;
                int alpha = Math.round(255 * nameStateList.get(i).currentProgress);

                textBackgroundRect.set(-NAME_BG_HORIZONTAL_OFFSET, -NAME_BG_TOP_OFFSET, staticLayout.getWidth() + NAME_BG_HORIZONTAL_OFFSET, staticLayout.getHeight() + NAME_BG_BOTTOM_OFFSET);

                canvas.save();
                canvas.translate(nameStateList.get(i).x, topOffset + NAME_BG_TOP_OFFSET);
                float scale = 0.8f + 0.2f * nameStateList.get(i).currentProgress;
                canvas.scale(scale, scale, textBackgroundRect.centerX(), textBackgroundRect.bottom + textBackgroundRect.height() * 0.5f);

                fragment.getResourceProvider().applyServiceShaderMatrix(cell.getMeasuredWidth(), cell.getBackgroundHeight(), cell.getX(), cell.getViewTop());
                float radius = textBackgroundRect.height() * 0.5f;
                int savedBackgroundAlpha = nameBackgroundPaint.getAlpha();
                nameBackgroundPaint.setAlpha(Math.round(savedBackgroundAlpha / 255f * alpha * 0.75f));
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

        public void openAnimation(@NonNull RectF srcCircleRect) {
            if (getParent() != null) {
                ViewGroup parentViewGroup = (ViewGroup) getParent();
                prevClipChildren = parentViewGroup.getClipChildren();
                parentViewGroup.setClipChildren(false);
            }

            recyclerView.setPivotX(0f);
            for (int i = 0; i < recyclerView.getChildCount(); ++i) {
                recyclerView.getChildAt(i).setScaleX(0f);
                recyclerView.getChildAt(i).setScaleY(0f);
            }

            final Path widthInterpolatorPath = new Path();
            widthInterpolatorPath.moveTo(0f, 0f);
            widthInterpolatorPath.cubicTo(0.3f, 0.0f, 0.0f, 1.2f, 0.6f, 1.0f);
            widthInterpolatorPath.quadTo(0.7f, 0.95f, 1.0f, 1.0f);
            Interpolator sizeInterpolator = PathInterpolatorCompat.create(widthInterpolatorPath);

            final Path topInterpolatorPath = new Path();
            topInterpolatorPath.moveTo(0f, 0f);
            topInterpolatorPath.cubicTo(0.2f, 0.2f, 0.0f, 1.2f, 0.4f, 1.0f);
            topInterpolatorPath.quadTo(0.5f, 0.95f, 1.0f, 1.0f);
            Interpolator topInterpolator = PathInterpolatorCompat.create(topInterpolatorPath);

            Interpolator circleInterpolator = input -> {
                if (input >= 0f && input < 0.4f) {
                    float value = Math.abs((input - 0.2f) * 5f);
                    value *= value;
                    return 1 - value;
                } else if (input < 0.7f) {
                    float value = input - 0.55f;
                    value *= value;
                    return value * 6.66f - 0.15f;
                }
                return 0.0f;
            };

            final Path circlePath = new Path();
            final Path leftConnectionPath = new Path();
            final Path rightConnectionPath = new Path();
            final Path intersectionPath = new Path();
            final PointF startLeftRectPoint = new PointF();
            final PointF startRightRectPoint = new PointF();
            final PointF tanLeftInterRectPoint = new PointF();
            final PointF tanRightInterRectPoint = new PointF();
            final float circleRadius = ChatMessageCell.SIDE_BUTTON_SIZE * 0.5f;
            ValueAnimator bubbleAnimator = ValueAnimator.ofFloat(0f, 1f);
            bubbleAnimator.addUpdateListener(animation -> {
                float progress = (float) animation.getAnimatedValue();
                circlePath.rewind();
                leftConnectionPath.rewind();
                rightConnectionPath.rewind();
                intersectionPath.rewind();
                bubblePaint.setAlpha(Math.round(255 * MathUtils.clamp(progress * 10f, 0.0f, 1.0f)));

                // prepare rect
                float sizeProgress = sizeInterpolator.getInterpolation(progress);
                bubbleRect.top = AndroidUtilities.lerp(srcCircleRect.top, recyclerView.getTop(), topInterpolator.getInterpolation(progress));
                bubbleRect.left = AndroidUtilities.lerp(srcCircleRect.left, recyclerView.getLeft(), sizeProgress);
                bubbleRect.right = bubbleRect.left + AndroidUtilities.lerp(srcCircleRect.width(), recyclerView.getWidth(), sizeProgress);
                bubbleRect.bottom = bubbleRect.top + AndroidUtilities.lerp(srcCircleRect.height(), recyclerView.getHeight(), sizeProgress);
                float bubbleRectRadius = bubbleRect.height() * 0.5f;
                bubblePath.rewind();
                bubblePath.addRoundRect(bubbleRect, bubbleRectRadius, bubbleRectRadius, Path.Direction.CW);

                // prepare list
                float recyclerViewScale = bubbleRect.width() / recyclerView.getMeasuredWidth();
                recyclerView.setScaleX(recyclerViewScale);
                recyclerView.setScaleY(recyclerViewScale);
                recyclerView.setTranslationX(bubbleRect.left - SHADOW_SPACE);
                recyclerView.setTranslationY(bubbleRect.top - recyclerView.getTop() + (bubbleRect.height() - recyclerView.getHeight() * recyclerViewScale) / 2);

                // prepare gradient
                float yTranslation = isListAboveReplyButton
                        ? bubbleRect.top + bubbleRect.height() - recyclerView.getHeight() + AndroidUtilities.dp(1)
                        : bubbleRect.top - recyclerView.getTop();
                bubbleGradientMatrix.setTranslate(0f, yTranslation);
                Shader shader = bubblePaint.getShader();
                if (shader != null) {
                    shader.setLocalMatrix(bubbleGradientMatrix);
                }

                // prepare side button
                float circleProgress = -circleInterpolator.getInterpolation(progress);
                circleRotationDegrees = circleProgress * 45f * (isListAboveReplyButton ? 1f : -1f);
                circleRect.set(srcCircleRect);
                circleRect.offset(0, circleProgress * circleRadius * (isListAboveReplyButton ? 1 : -1));
                if (circleRect.bottom > bubbleRect.bottom && isListAboveReplyButton || circleRect.top < bubbleRect.top) {
                    circlePath.addCircle(srcCircleRect.centerX(), circleRect.centerY(), circleRadius, Path.Direction.CW);
                }

                if (isListAboveReplyButton && bubbleRect.bottom < circleRect.bottom) {
                    float circleIntersectionHeight = circleRect.centerY() - bubbleRect.bottom;
                    float circleIntersectionWidth = (float) Math.sqrt(circleRadius * circleRadius - circleIntersectionHeight * circleIntersectionHeight);
                    float additionalAngleRad = (float) Math.toRadians(AndroidUtilities.lerpAngle(10, 30, MathUtils.clamp(circleIntersectionHeight / circleRadius, 0f, 1f)));
                    double leftAngleRad = Math.abs(circleIntersectionHeight) >= circleRadius
                            ? Math.PI / 2
                            : Math.atan2(circleIntersectionHeight, -circleIntersectionWidth);
                    leftAngleRad += additionalAngleRad;
                    float xLeftTangentStart = circleRect.centerX() + (float) (circleRadius * cos(leftAngleRad));
                    float yLeftTangentStart = circleRect.centerY() - (float) (circleRadius * sin(leftAngleRad));
                    float kLeft = -1 / ((circleRect.centerY() - yLeftTangentStart) / (xLeftTangentStart - circleRect.centerX()));

                    double rightAngleRad = Math.abs(circleIntersectionHeight) >= circleRadius
                            ? Math.PI / 2
                            : Math.atan2(circleIntersectionHeight, circleIntersectionWidth);
                    rightAngleRad -= additionalAngleRad;
                    float xRightTangentStart = circleRect.centerX() + (float) (circleRadius * cos(rightAngleRad));
                    float yRightTangentStart = circleRect.centerY() - (float) (circleRadius * sin(rightAngleRad));
                    float kRight = -1 / ((circleRect.centerY() - yRightTangentStart) / (xRightTangentStart - circleRect.centerX()));

                    if (AndroidUtilities.fillLinesIntersection(kLeft, 0, 0, yLeftTangentStart - bubbleRect.bottom, tanLeftInterRectPoint) &&
                            AndroidUtilities.fillLinesIntersection(kRight, 0, 0, yRightTangentStart - bubbleRect.bottom, tanRightInterRectPoint) &&
                            tanLeftInterRectPoint.x + xLeftTangentStart - (tanRightInterRectPoint.x + xRightTangentStart) <= AndroidUtilities.dp(10) &&
                            tanLeftInterRectPoint.x + xLeftTangentStart >= bubbleRect.left && tanRightInterRectPoint.x + xRightTangentStart <= bubbleRect.right
                    ) {
                        // prepare left path
                        tanLeftInterRectPoint.x += xLeftTangentStart;
                        tanLeftInterRectPoint.y = yLeftTangentStart - tanLeftInterRectPoint.y;
                        float tanLeftLineSize = (float) Math.hypot(tanLeftInterRectPoint.x - xLeftTangentStart, tanLeftInterRectPoint.y - yLeftTangentStart);
                        startLeftRectPoint.x = Math.max(bubbleRect.left, tanLeftInterRectPoint.x - tanLeftLineSize);
                        startLeftRectPoint.y = tanLeftInterRectPoint.y;
                        if (startLeftRectPoint.x < bubbleRect.left + bubbleRectRadius) {
                            float xDist = bubbleRect.left + bubbleRectRadius - startLeftRectPoint.x;
                            float yDist = (float) Math.sqrt(bubbleRectRadius * bubbleRectRadius - xDist * xDist);
                            startLeftRectPoint.y = bubbleRect.centerY() + (Float.isNaN(yDist) ? 0 : yDist);
                        }
                        leftConnectionPath.moveTo(startLeftRectPoint.x, startLeftRectPoint.y);
                        leftConnectionPath.lineTo(xLeftTangentStart, yLeftTangentStart);
                        leftConnectionPath.lineTo(circleRect.centerX(), yLeftTangentStart);
                        leftConnectionPath.lineTo(circleRect.centerX(), tanLeftInterRectPoint.y);
                        leftConnectionPath.close();
                        leftConnectionPath.moveTo(startLeftRectPoint.x, startLeftRectPoint.y);
                        leftConnectionPath.quadTo(tanLeftInterRectPoint.x, tanLeftInterRectPoint.y, xLeftTangentStart, yLeftTangentStart);
                        leftConnectionPath.close();

                        // prepare right path
                        tanRightInterRectPoint.x += xRightTangentStart;
                        tanRightInterRectPoint.y = yRightTangentStart - tanRightInterRectPoint.y;
                        float tanRightLineSize = (float) Math.hypot(tanRightInterRectPoint.x - xRightTangentStart, tanRightInterRectPoint.y - yRightTangentStart);
                        startRightRectPoint.x = Math.min(bubbleRect.right, tanRightInterRectPoint.x + tanRightLineSize);
                        startRightRectPoint.y = tanRightInterRectPoint.y;
                        if (startRightRectPoint.x > bubbleRect.right - bubbleRectRadius) {
                            float xDist = startRightRectPoint.x - (bubbleRect.right - bubbleRectRadius);
                            float yDist = (float) Math.sqrt(bubbleRectRadius * bubbleRectRadius - xDist * xDist);
                            startRightRectPoint.y = bubbleRect.centerY() + (Float.isNaN(yDist) ? 0 : yDist);
                        }
                        rightConnectionPath.moveTo(startRightRectPoint.x, startRightRectPoint.y);
                        rightConnectionPath.lineTo(xRightTangentStart, yRightTangentStart);
                        rightConnectionPath.lineTo(circleRect.centerX(), yRightTangentStart);
                        rightConnectionPath.lineTo(circleRect.centerX(), tanRightInterRectPoint.y);
                        rightConnectionPath.close();
                        rightConnectionPath.moveTo(startRightRectPoint.x, startRightRectPoint.y);
                        rightConnectionPath.quadTo(tanRightInterRectPoint.x, tanRightInterRectPoint.y, xRightTangentStart, yRightTangentStart);
                        rightConnectionPath.close();

                        // fill intersection
                        intersectionPath.moveTo(startLeftRectPoint.x, startLeftRectPoint.y);
                        intersectionPath.lineTo(circleRect.centerX(), Math.max(tanLeftInterRectPoint.y, tanRightInterRectPoint.y));
                        intersectionPath.lineTo(startRightRectPoint.x, startRightRectPoint.y);
                        intersectionPath.lineTo(startLeftRectPoint.x, startLeftRectPoint.y);
                    }
                } else if (!isListAboveReplyButton && bubbleRect.top > circleRect.top) {
                    float circleIntersectionHeight = circleRect.centerY() - bubbleRect.top;
                    float circleIntersectionWidth = (float) Math.sqrt(circleRadius * circleRadius - circleIntersectionHeight * circleIntersectionHeight);
                    float additionalAngleRad = (float) Math.toRadians(AndroidUtilities.lerpAngle(10, 30, MathUtils.clamp(Math.abs(circleIntersectionHeight) / circleRadius, 0f, 1f)));
                    double leftAngleRad = Math.abs(circleIntersectionHeight) >= circleRadius
                            ? Math.PI * 3 / 2
                            : Math.atan2(circleIntersectionHeight, -circleIntersectionWidth);
                    leftAngleRad -= additionalAngleRad;
                    float xLeftTangentStart = circleRect.centerX() + (float) (circleRadius * cos(leftAngleRad));
                    float yLeftTangentStart = circleRect.centerY() - (float) (circleRadius * sin(leftAngleRad));
                    float kLeft = -1 / ((yLeftTangentStart - circleRect.centerY()) / (xLeftTangentStart - circleRect.centerX()));

                    double rightAngleRad = Math.abs(circleIntersectionHeight) >= circleRadius
                            ? Math.PI * 3 / 2
                            : Math.atan2(circleIntersectionHeight, circleIntersectionWidth);
                    rightAngleRad += additionalAngleRad;
                    float xRightTangentStart = circleRect.centerX() + (float) (circleRadius * cos(rightAngleRad));
                    float yRightTangentStart = circleRect.centerY() - (float) (circleRadius * sin(rightAngleRad));
                    float kRight = -1 / ((yRightTangentStart - circleRect.centerY()) / (xRightTangentStart - circleRect.centerX()));

                    if (AndroidUtilities.fillLinesIntersection(kLeft, 0, 0, bubbleRect.top - yLeftTangentStart, tanLeftInterRectPoint) &&
                            AndroidUtilities.fillLinesIntersection(kRight, 0, 0, bubbleRect.top - yRightTangentStart, tanRightInterRectPoint) &&
                            (tanLeftInterRectPoint.x + xLeftTangentStart) - (tanRightInterRectPoint.x + xRightTangentStart) <= AndroidUtilities.dp(10) &&
                            tanLeftInterRectPoint.x + xLeftTangentStart >= bubbleRect.left && tanRightInterRectPoint.x + xRightTangentStart <= bubbleRect.right
                    ) {
                        // prepare left path
                        tanLeftInterRectPoint.x += xLeftTangentStart;
                        tanLeftInterRectPoint.y = yLeftTangentStart + tanLeftInterRectPoint.y;
                        float tanLeftLineSize = (float) Math.hypot(tanLeftInterRectPoint.x - xLeftTangentStart, tanLeftInterRectPoint.y - yLeftTangentStart);
                        startLeftRectPoint.x = Math.max(bubbleRect.left, tanLeftInterRectPoint.x - tanLeftLineSize);
                        startLeftRectPoint.y = tanLeftInterRectPoint.y;
                        if (startLeftRectPoint.x < bubbleRect.left + bubbleRectRadius) {
                            float xDist = bubbleRect.left + bubbleRectRadius - startLeftRectPoint.x;
                            float yDist = (float) Math.sqrt(bubbleRectRadius * bubbleRectRadius - xDist * xDist);
                            startLeftRectPoint.y = bubbleRect.centerY() - (Float.isNaN(yDist) ? 0 : yDist);
                        }
                        leftConnectionPath.moveTo(startLeftRectPoint.x, startLeftRectPoint.y);
                        leftConnectionPath.lineTo(xLeftTangentStart, yLeftTangentStart);
                        leftConnectionPath.lineTo(circleRect.centerX(), yLeftTangentStart);
                        leftConnectionPath.lineTo(circleRect.centerX(), tanLeftInterRectPoint.y);
                        leftConnectionPath.close();
                        leftConnectionPath.moveTo(startLeftRectPoint.x, startLeftRectPoint.y);
                        leftConnectionPath.quadTo(tanLeftInterRectPoint.x, tanLeftInterRectPoint.y, xLeftTangentStart, yLeftTangentStart);
                        leftConnectionPath.close();

                        // prepare right path
                        tanRightInterRectPoint.x += xRightTangentStart;
                        tanRightInterRectPoint.y = yRightTangentStart + tanRightInterRectPoint.y;
                        float tanRightLineSize = (float) Math.hypot(tanRightInterRectPoint.x - xRightTangentStart, tanRightInterRectPoint.y - yRightTangentStart);
                        startRightRectPoint.x = Math.min(bubbleRect.right, tanRightInterRectPoint.x + tanRightLineSize);
                        startRightRectPoint.y = tanRightInterRectPoint.y;
                        if (bubbleRect.right - bubbleRectRadius < startRightRectPoint.x) {
                            float xDist = startRightRectPoint.x - (bubbleRect.right - bubbleRectRadius);
                            float yDist = (float) Math.sqrt(bubbleRectRadius * bubbleRectRadius - xDist * xDist);
                            startRightRectPoint.y = bubbleRect.centerY() - (Float.isNaN(yDist) ? 0 : yDist);
                        }
                        rightConnectionPath.moveTo(startRightRectPoint.x, startRightRectPoint.y);
                        rightConnectionPath.lineTo(xRightTangentStart, yRightTangentStart);
                        rightConnectionPath.lineTo(circleRect.centerX(), yRightTangentStart);
                        rightConnectionPath.lineTo(circleRect.centerX(), tanRightInterRectPoint.y);
                        rightConnectionPath.close();
                        rightConnectionPath.moveTo(startRightRectPoint.x, startRightRectPoint.y);
                        rightConnectionPath.quadTo(tanRightInterRectPoint.x, tanRightInterRectPoint.y, xRightTangentStart, yRightTangentStart);
                        rightConnectionPath.close();
                    }
                }

                bubblePath.op(leftConnectionPath, Path.Op.UNION);
                bubblePath.op(rightConnectionPath, Path.Op.UNION);
                bubblePath.op(intersectionPath, Path.Op.UNION);
                bubblePath.op(circlePath, Path.Op.UNION);
                bubblePath.close();

                invalidate();
            });
            bubbleAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    super.onAnimationStart(animation);
                    setVisibility(VISIBLE);
                    setCellSideButtonVisible(false);
                }
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    isOpenAnimationFinished = true;
                }
            });
            bubbleAnimator.setDuration(750);
            bubbleAnimator.setInterpolator(new LinearInterpolator());

            openAnimator = new AnimatorSet();
            openAnimator.play(bubbleAnimator);

            int startDelay = 100;
            int childCount = Math.min(DEFAULT_ITEM_COUNT, recyclerView.getChildCount());
            for (int i = childCount / 2; i >= 0; --i) {
                final int index = i;
                ValueAnimator childScaleAnimator = ValueAnimator.ofFloat(1.0f);
                childScaleAnimator.addUpdateListener(animation -> {
                    float scale = (float) animation.getAnimatedValue();
                    recyclerView.getChildAt(index).setScaleX(scale);
                    recyclerView.getChildAt(index).setScaleY(scale);
                    if (index != childCount - index - 1) {
                        recyclerView.getChildAt(childCount - index - 1).setScaleX(scale);
                        recyclerView.getChildAt(childCount - index - 1).setScaleY(scale);
                    }
                });
                childScaleAnimator.setDuration((long)(bubbleAnimator.getDuration() * 0.5f));
                childScaleAnimator.setInterpolator(sizeInterpolator);
                childScaleAnimator.setStartDelay(startDelay);
                startDelay += (int)(childScaleAnimator.getDuration() / 6);
                openAnimator.play(childScaleAnimator);
            }

            openAnimator.start();
        }

        private void closeAnimation(@Nullable View view) {
            if (openAnimator != null) {
                openAnimator.cancel();
            }

            ValueAnimator closeAnimator = ValueAnimator.ofFloat(1f, 0f);
            closeAnimator.addUpdateListener(animation -> {
                float progress = (float) animation.getAnimatedValue();
                recyclerView.setAlpha(progress);
                int paintAlpha = Math.round(255 * progress);
                bubblePaint.setAlpha(paintAlpha);
                nameBackgroundPaint.setAlpha(paintAlpha);
                blurredBitmapPaint.setAlpha(paintAlpha);
                invalidate();
            });
            closeAnimator.addListener(new AnimatorListenerAdapter() {

                @Override
                public void onAnimationStart(Animator animation) {
                    super.onAnimationStart(animation);
                    setCellSideButtonVisible(true);
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    if (getParent() != null) {
                        ViewGroup parentViewGroup = (ViewGroup) getParent();
                        parentViewGroup.removeView(ReplyViewGroup.this);
                        parentViewGroup.setClipChildren(prevClipChildren);
                    }
                    if (delegate != null) {
                        delegate.onClose();
                    }
                    if (blurredBitmap != null) {
                        blurredBitmap.recycle();
                    }
                    blurredBitmap = null;
                }
            });
            closeAnimator.setDuration(200);
            closeAnimator.start();

            int savedViewVisibility = View.VISIBLE;
            if (view != null) {
                savedViewVisibility = view.getVisibility();
                view.setVisibility(View.GONE);
            }
            int maxBlurRadius = 12;
            int bitmapWidth = (int) (getWidth() / BLUR_DOWNSCALE) + maxBlurRadius * 2;
            int bitmapHeight = (int) (getHeight() / BLUR_DOWNSCALE) + maxBlurRadius * 2;
            blurredBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
            Canvas blurredCanvas = new Canvas(blurredBitmap);
            blurredCanvas.scale(1f / BLUR_DOWNSCALE, 1f / BLUR_DOWNSCALE);
            isDrawToBitmapCanvas = true;
            draw(blurredCanvas);
            isDrawToBitmapCanvas = false;
            if (view != null) {
                view.setVisibility(savedViewVisibility);
            }
            Utilities.themeQueue.postRunnable(() -> {
                Utilities.stackBlurBitmap(blurredBitmap, 2);
                AndroidUtilities.runOnUIThread(closeAnimator::start);
            });
        }

        private void setCellSideButtonVisible(boolean isVisible) {
            if (isVisible) {
                cell.drawSideButton = prevDrawSideButton;
            } else {
                prevDrawSideButton = cell.drawSideButton;
                cell.drawSideButton = 0;
            }
            fragment.getChatListView().invalidate();
        }

        private void onListChildSelected(int position) {
            if (position == selectedChildViewPosition) {
                return;
            }

            if (dialogSelectedAnimator != null) {
                dialogSelectedAnimator.cancel();
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

            dialogSelectedAnimator = ValueAnimator.ofFloat(0f, 1f);
            dialogSelectedAnimator.addUpdateListener(animation -> {
                float progress = (float) animation.getAnimatedValue();
                for (int i = 0; i < viewStates.length; ++i) {
                    viewStates[i].apply(progress);
                }
                for (int i = 0; i < nameStateList.size(); ++i) {
                    nameStateList.get(i).apply(progress);
                }
                invalidate();
            });
            dialogSelectedAnimator.addListener(new AnimatorListenerAdapter() {
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
            dialogSelectedAnimator.setDuration(150L);
            dialogSelectedAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            dialogSelectedAnimator.start();
        }

        public void passTouchEvent(MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_CANCEL || !isOpenAnimationFinished) {
                return;
            }
            if (ev.getAction() == MotionEvent.ACTION_UP) {
                View view = recyclerView.getChildAt(selectedChildViewPosition);
                closeAnimation(view);
                shareToDialog(view);
                isLongTapped = false;
                return;
            } else {
                isLongTapped = true;
            }
            MotionEvent newEvent = MotionEvent.obtain(ev);
            newEvent.offsetLocation(-recyclerView.getLeft(), -recyclerView.getTop());
            recyclerView.onTouchEvent(newEvent);
        }

        private void shareToDialog(View view) {
            int position = recyclerView.getChildAdapterPosition(view);
            final TLRPC.Dialog dialog = adapter != null ? adapter.getItemAt(position) : null;
            if (dialog == null) {
                return;
            }

            ArrayList<MessageObject> array = new ArrayList<>();
            array.add(cell.getMessageObject());
            SendMessagesHelper.getInstance(UserConfig.selectedAccount).sendMessage(array, dialog.id, true, false, true, 0);

            Bulletin bulletin = BulletinFactory.createForwardedBulletin(fragment.getContext(), fragment.getLayoutContainer(), 1, dialog.id, 1, fragment.getThemedColor(Theme.key_undo_background), fragment.getThemedColor(Theme.key_undo_infoColor));
            Bulletin.Layout bulletinLayout = bulletin.getLayout();
            if (bulletinLayout instanceof Bulletin.LottieLayout) {
                Bulletin.LottieLayout lottieLayout = (Bulletin.LottieLayout) bulletinLayout;
                CharSequence sequence = lottieLayout.textView.getText();
                if (sequence instanceof SpannedString && UserConfig.getInstance(UserConfig.selectedAccount).clientUserId != dialog.id) {
                    SpannedString string = (SpannedString) sequence;
                    TypefaceSpan[] typefaceSpans = string.getSpans(0, string.length(), TypefaceSpan.class);
                    if (typefaceSpans != null && typefaceSpans.length > 0) {
                        int start = string.getSpanStart(typefaceSpans[0]);
                        int end = string.getSpanEnd(typefaceSpans[0]);
                        SpannableStringBuilder builder = new SpannableStringBuilder(string);
                        builder.setSpan(fragment.getThemedColor(Theme.key_undo_infoColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        builder.setSpan(new ClickableSpan() {
                            @Override
                            public void onClick(@NonNull View widget) {
                                onBulletinNameClicked(dialog);
                            }
                            @Override
                            public void updateDrawState(@NonNull TextPaint ds) {
                                super.updateDrawState(ds);
                                ds.setUnderlineText(false);
                            }
                        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        lottieLayout.textView.setText(builder);
                    }
                }
                lottieLayout.isAutoPlay = false;
                bulletinLayout.addCallback(getBulletinCallback(((ViewGroup) view).getChildAt(0)));
            }
            bulletin.show();
        }

        private void onBulletinNameClicked(TLRPC.Dialog dialog) {
            TLRPC.User user = null;
            TLRPC.Chat chat = null;
            if (DialogObject.isUserDialog(dialog.id)) {
                user = MessagesController.getInstance(UserConfig.selectedAccount).getUser(dialog.id);
            } else {
                chat = MessagesController.getInstance(UserConfig.selectedAccount).getChat(-dialog.id);
            }
            fragment.getMessagesController().openChatOrProfileWith(user, chat, fragment, 1, false);
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

        @SuppressLint("NotifyDataSetChanged")
        public void fetchDialogs() {
            int currentAccount = UserConfig.selectedAccount;
            long selfUserId = UserConfig.getInstance(currentAccount).clientUserId;
            ArrayList<TLRPC.Dialog> allDialogs = new ArrayList<>(MessagesController.getInstance(currentAccount).getAllDialogs());
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
            for (int i = dialogs.size() - 1; i >= DEFAULT_ITEM_COUNT; --i) {
                dialogs.remove(i);
            }

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
