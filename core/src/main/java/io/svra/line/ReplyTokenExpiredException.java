package io.svra.line;

/**
 * 那個 reply token 不能用了——過期，或已經用過。
 *
 * <p>要跟其他失敗分開，是因為<b>正確的處置相反</b>：
 * 網路抖動、LINE 的 5xx 要退避重試（重試時 token 可能還活著）；
 * 而 token 失效重試一萬次也不會好，該做的是<b>立刻改用推播</b>——
 * 使用者照樣收得到，只是吃掉一則免費額度。
 *
 * <p>不分開的話只有兩種結局，而兩種都不好：一律重試會讓使用者
 * <b>完全收不到回覆</b>（token 單次使用，五次重試必定全敗）；
 * 一律改推播則會在一次網路抖動時，白白放棄那則本來免費的回覆。
 */
public class ReplyTokenExpiredException extends RuntimeException {

    public ReplyTokenExpiredException(String message) {
        super(message);
    }
}
