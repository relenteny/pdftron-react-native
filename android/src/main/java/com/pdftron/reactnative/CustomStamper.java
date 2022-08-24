package com.pdftron.reactnative;

import android.net.Uri;
import androidx.annotation.NonNull;

import com.pdftron.pdf.Annot;
import com.pdftron.pdf.PDFViewCtrl;
import com.pdftron.pdf.tools.Stamper;
import com.pdftron.pdf.tools.ToolManager;

import com.pdftron.filters.SecondaryFileFilter;
import com.pdftron.pdf.PDFDoc;
import com.pdftron.sdf.Obj;
import com.pdftron.sdf.ObjSet;
import com.pdftron.pdf.Image;
import com.pdftron.pdf.Page;
import com.pdftron.pdf.Rect;
import com.pdftron.pdf.utils.Utils;
import com.pdftron.common.Matrix2D;
import com.pdftron.pdf.Point;
import com.pdftron.pdf.utils.AnalyticsHandlerAdapter;
import com.pdftron.pdf.annots.Markup;
import com.pdftron.pdf.PageSet;

public class CustomStamper extends Stamper {
    private Uri mUri;

    public CustomStamper(@NonNull PDFViewCtrl ctrl) {
        super(ctrl);
    }

    public void setUri(Uri uri) {
        mUri = uri;
    }

    @Override
    protected void addStamp() {
        if (mUri != null) {
            customCreateImageStamp(mUri, 0, "");
        } else {
            mNextToolMode = getToolMode();
        }
    }

    /**
     * Creates an image stamp.
     *
     * @param uri           The URI
     * @param imageRotation The image rotation (e.g. 0, 90, 180, 270)
     * @param filePath      The file path
     * @return True if an image stamp is created
     */
    @SuppressWarnings("SuspiciousNameCombination")
    public boolean customCreateImageStamp(Uri uri, int imageRotation, String filePath) {
        boolean shouldUnlock = false;
        SecondaryFileFilter filter = null;
        try {
            mPdfViewCtrl.docLock(true);
            shouldUnlock = true;
            PDFDoc doc = mPdfViewCtrl.getDoc();

            // Another method to compress the image - resample the bitmap
           /* ByteArrayOutputStream out = new ByteArrayOutputStream();
            final BitmapFactory.Options bitmapOptions = new BitmapFactory.Options();
            bitmapOptions.inDensity = 10;
            bitmapOptions.inTargetDensity = 1;

            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
            Bitmap decoded = BitmapFactory.decodeStream(new ByteArrayInputStream(out.toByteArray()), null, bitmapOptions);
            decoded.setDensity(Bitmap.DENSITY_NONE);
            decoded = Bitmap.createBitmap(decoded);

            Image img = Image.create(doc.getSDFDoc(), decoded);
            */

            // create a filter to pass the image into core
            filter = new SecondaryFileFilter(mPdfViewCtrl.getContext(), uri);

            // set encoder hints
            ObjSet hintSet = new ObjSet();
            Obj encoderHints = hintSet.createArray();
            encoderHints.pushBackName("JPEG");
            encoderHints.pushBackName("Quality");
            encoderHints.pushBackNumber(85);

            // create an image
            Image img = Image.create(doc.getSDFDoc(), filter, encoderHints);

            int pageNum;
            if (mTargetPoint != null) {
                pageNum = mPdfViewCtrl.getPageNumberFromScreenPt(mTargetPoint.x, mTargetPoint.y);
                if (pageNum <= 0) {
                    pageNum = mPdfViewCtrl.getCurrentPage();
                }
            } else {
                pageNum = mPdfViewCtrl.getCurrentPage();
            }

            if (pageNum <= 0) {
                return false;
            }

            /////////////////   set stamp size    ////////////////
            Page page = doc.getPage(pageNum);
            int viewRotation = mPdfViewCtrl.getPageRotation();

            Rect pageViewBox = page.getBox(mPdfViewCtrl.getPageBox());
            Rect pageCropBox = page.getCropBox();
            int pageRotation = page.getRotation();

            // get screen height and width
            android.graphics.Point size = new android.graphics.Point();
            Utils.getDisplaySize(mPdfViewCtrl.getContext(), size);
            int screenWidth = size.x < size.y ? size.x : size.y;
            int screenHeight = size.x < size.y ? size.y : size.x;

            // calculate the max image size based off of screen width and height
            double maxImageHeightPixels = screenHeight * 0.25;
            double maxImageWidthPixels = screenWidth * 0.25;

            // convert the max image size in pixels to page units
            double[] point1 = mPdfViewCtrl.convScreenPtToPagePt(0, 0, pageNum);
            double[] point2 = mPdfViewCtrl.convScreenPtToPagePt(20, 20, pageNum);

            double pixelsToPageRatio = Math.abs(point1[0] - point2[0]) / 20;
            double maxImageHeightPage = maxImageHeightPixels * pixelsToPageRatio;
            double maxImageWidthPage = maxImageWidthPixels * pixelsToPageRatio;

            // scale stamp
            double stampWidth = img.getImageWidth();
            double stampHeight = img.getImageHeight();
            if (imageRotation == 90 || imageRotation == 270) {
                double temp = stampWidth;
                stampWidth = stampHeight;
                stampHeight = temp;
            }

            double pageWidth = pageViewBox.getWidth();
            double pageHeight = pageViewBox.getHeight();
            if (pageRotation == Page.e_90 || pageRotation == Page.e_270) {
                double temp = pageWidth;
                pageWidth = pageHeight;
                pageHeight = temp;
            }

            // if page width or height is smaller than the desired image size,
            // set desired image size to the page width or height
            if (pageWidth < maxImageWidthPage) {
                maxImageWidthPage = pageWidth;
            }
            if (pageHeight < maxImageHeightPage) {
                maxImageHeightPage = pageHeight;
            }

            double scaleFactor = Math.min(maxImageWidthPage / stampWidth, maxImageHeightPage / stampHeight);
            // stampWidth *= scaleFactor;
            // stampHeight *= scaleFactor;

            // Stamp width and height are relative to the view rotation, not screen rotation
            if (viewRotation == Page.e_90 || viewRotation == Page.e_270) {
                double temp = stampWidth;
                stampWidth = stampHeight;
                stampHeight = temp;
            }

            com.pdftron.pdf.Stamper stamper = new com.pdftron.pdf.Stamper(com.pdftron.pdf.Stamper.e_absolute_size, stampWidth, stampHeight);

            //////////////////   set stamp position   //////////////////////
            if (mTargetPoint != null) {
                // get target point in page coordinates
                double[] pageTarget = mPdfViewCtrl.convScreenPtToPagePt(mTargetPoint.x, mTargetPoint.y, pageNum);
                Matrix2D mtx = page.getDefaultMatrix();
                Point pageTargetPoint = mtx.multPoint(pageTarget[0], pageTarget[1]);

                // set position to be relative to bottom-left hand corner of document
                stamper.setAlignment(com.pdftron.pdf.Stamper.e_horizontal_left, com.pdftron.pdf.Stamper.e_vertical_bottom);

                // move the page target so that the middle of the image aligns with the
                // location tapped by the user.
                pageTargetPoint.x = pageTargetPoint.x - (stampWidth / 2);
                pageTargetPoint.y = pageTargetPoint.y - (stampHeight / 2);

                // get page height and width to determine if entire image will fit on page
                // if not, move image appropriately so that it will
                double leftEdge = pageViewBox.getX1() - pageCropBox.getX1();
                double bottomEdge = pageViewBox.getY1() - pageCropBox.getY1();
                if (pageTargetPoint.x > leftEdge + pageWidth - stampWidth) {
                    pageTargetPoint.x = leftEdge + pageWidth - stampWidth;
                }
                if (pageTargetPoint.x < leftEdge) {
                    pageTargetPoint.x = leftEdge;
                }

                if (pageTargetPoint.y > bottomEdge + pageHeight - stampHeight) {
                    pageTargetPoint.y = bottomEdge + pageHeight - stampHeight;
                }
                if (pageTargetPoint.y < bottomEdge) {
                    pageTargetPoint.y = bottomEdge;
                }

                stamper.setPosition(pageTargetPoint.x, pageTargetPoint.y);
            } else {
                // stamp image in middle of screen if target point returns null
                stamper.setPosition(0, 0);
            }

            // set as annotation
            stamper.setAsAnnotation(true);

            // set image rotation
            int stampRotation = (4 - viewRotation) % 4; // 0 = 0, 90 = 1; 180 = 2, and 270 = 3
            stamper.setRotation(stampRotation * 90.0 + imageRotation);

            // stamp image
            stamper.stampImage(doc, img, new PageSet(pageNum));

            // update PDF to show stamp
            int numAnnots = page.getNumAnnots();
            Annot annot = page.getAnnot(numAnnots - 1);
            Obj obj = annot.getSDFObj();
            obj.putNumber(com.pdftron.pdf.tools.Stamper.STAMPER_ROTATION_ID, 0);

            if (annot.isMarkup()) {
                Markup markup = new Markup(annot);
                setAuthor(markup);
            }

            setAnnot(annot, pageNum);
            buildAnnotBBox();

            // Note: we don't select the annotation after finished with stamper
            // creation, so shouldn't set mAnnot

            mPdfViewCtrl.update(annot, pageNum);
            raiseAnnotationAddedEvent(annot, pageNum);
            return true;
        } catch (Exception e) {
            AnalyticsHandlerAdapter.getInstance().sendException(e);
            return false;
        } finally {
            Utils.closeQuietly(filter);
            if (shouldUnlock) {
                mPdfViewCtrl.docUnlock();
            }
            // reset target point
            mTargetPoint = null;
            safeSetNextToolMode();
        }
    }
}
