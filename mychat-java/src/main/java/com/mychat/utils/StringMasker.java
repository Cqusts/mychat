package com.mychat.utils;

public class StringMasker {

    public static String maskPhone(String phone) {
        if (phone == null || !phone.matches("1[3-9]\\d{9}")) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
