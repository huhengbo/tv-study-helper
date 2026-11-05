package de.huyc.studyhelper.quiz

/** 答题界面的三种用法。 */
enum class QuizMode {
    /** 观看时间到了的锁屏答题：返回键无效，答对才解锁。 */
    GATE,
    /** 家长「试一试」：返回键可退出，不改动计时，也不发奖励。 */
    PREVIEW,
    /** 孩子在电视首页主动学习：返回键可退出，答完得贴纸和加时，不动计时和锁定状态。 */
    STUDY,
}
