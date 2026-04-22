package jarmail;

public class NetworkHelper {
    public static final String BB_SUFFIX = ";deviceside=true";

    public static String makeBBUrl(String url) {
        return url + BB_SUFFIX;
    }
}