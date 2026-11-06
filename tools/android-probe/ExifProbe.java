import android.media.ExifInterface;

import java.io.FileInputStream;

/**
 * 平台 ExifInterface 探针：用**系统自身的实现**判定文件里有没有「可用经纬度」。
 *
 * 为什么要它：本工具位置判据的核心是「GPS IFD 非空 ≠ 能解出坐标」。
 * 只查段是否存在会误判（B 组夹具就是这种形态）；只有平台 ExifInterface
 * 的 getLatLong() 才能给出与相册/第三方应用一致的答案。
 *
 * 注意：必须用 InputStream 构造器。在 app_process 里用 (String) 或
 * (FileDescriptor) 构造器会走 FileUtils.convertToModernFd → 依赖
 * ActivityThread 的 Context，直接 NPE。
 *
 * 编译/运行见 tools/verify-location-exif.sh；也可手动：
 *   javac --release 17 -cp <android.jar> -d classes ExifProbe.java
 *   d8 --release --lib <android.jar> --min-api 29 --output dex classes/ExifProbe.class
 *   adb push dex/classes.dex /data/local/tmp/vlc_exifprobe/classes.dex
 *   adb shell "CLASSPATH=/data/local/tmp/vlc_exifprobe/classes.dex app_process /system/bin ExifProbe <files...>"
 */
public class ExifProbe {
    public static void main(String[] args) {
        int usable = 0;
        for (String path : args) {
            try (FileInputStream fis = new FileInputStream(path)) {
                ExifInterface ex = new ExifInterface(fis);
                float[] ll = new float[2];
                boolean ok = ex.getLatLong(ll);
                if (ok) usable++;
                System.out.println("FILE " + path);
                System.out.println("  getLatLong=" + (ok ? (ll[0] + "," + ll[1]) : "null/false"));
                System.out.println("  TAG_GPS_LATITUDE=" + ex.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                        + " ref=" + ex.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF));
                System.out.println("  TAG_GPS_LONGITUDE=" + ex.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
                        + " ref=" + ex.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF));
                System.out.println("  make=" + ex.getAttribute(ExifInterface.TAG_MAKE)
                        + " model=" + ex.getAttribute(ExifInterface.TAG_MODEL));
            } catch (Throwable t) {
                System.out.println("FILE " + path + " EXCEPTION " + t);
                t.printStackTrace(System.out);
            }
        }
        System.out.println("USABLE_COORDS=" + usable + "/" + args.length);
    }
}
