package com.df.entity;

import java.io.Serializable;

/**
 * @author Lin
 * @create 2020/10/10
 * @since 1.0.0
 * (功能)：
 */
public class User implements Serializable {
    private long id;
    private String username;
    private String email;
    private String tel;

    public User(){}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getTel() {
        return tel;
    }

    public void setTel(String tel) {
        this.tel = tel;
    }

    @Override
    public String toString() {
        return "User{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", email='" + email + '\'' +
                ", tel='" + tel + '\'' +
                '}';
    }

    public User(Long id, String username, String email, String tel) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.tel = tel;
    }
}
