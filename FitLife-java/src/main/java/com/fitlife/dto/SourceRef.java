package com.fitlife.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一条可溯源引用。
 *
 * <p>前端把 url 渲染成可点击链接；evidenceLevel 用来表达证据强度，
 * 避免把「社区整理」和「ACSM 立场声明」显示成同一份量。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceRef {

    /** KNOWLEDGE 知识条目 / EXERCISE 动作来源 / FOOD 食物数据源 / GUIDELINE 通用指南 */
    private String kind;

    /** 条目代码或动作 id，便于回查 */
    private String refCode;

    /** 名称，如「渐进超负荷原则」「杠铃深蹲」 */
    private String title;

    /** 发布机构，如 ACSM / NSCA / 中国疾病预防控制中心营养与健康所 */
    private String publisher;

    private String sourceTitle;

    /** 可点击寻源链接 */
    private String url;

    /** A 指南与立场声明 / B 教材与综述 / C 社区整理 */
    private String evidenceLevel;

    /** 本计划里用到它的位置，如「V1 靶心率校验」「周三午餐」 */
    private String usedFor;
}