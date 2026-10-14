package com.bx.implatform.util;

/**
 * @author Blue
 * @version 1.0
 */
public class ConvUtil {

    public static String buildConvKey(Long userId1, Long userId2) {
        return Math.min(userId1, userId2) + "_" + Math.max(userId1, userId2);
    }
}
