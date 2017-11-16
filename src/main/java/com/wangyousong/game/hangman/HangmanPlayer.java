package com.wangyousong.game.hangman;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * HangmanPlayer核心算法描述:<br/>
 <ol>
    <li>
        初始化:
        <ul>
            <li>
                (1)将words根据难度分成四级字典：A|B|C|D 。
                <p>
                 (注：难度划分依据,当前猜的第几个词,来确定单词的长度，来划分A~D，共四级.)
                 <ul>
                    <li>1st to 20th word : length <= 5 letters</li>
                    <li>21st to 40th word : length <= 8 letters</li>
                    <li>41st to 60th word : length <= 12 letters</li>
                    <li>61st to 80th word : length > 12 letters</li>
                 </ul>
                </p>
            </li>
            <li>(2)字母词频顺序集合:EAOIUTSRNHLDCMFPGWYBVKXJQZ</li>
        </ul>
    </li>

    <li>
        根据当前猜的第几个词，来锁定是哪个字典(A|B|C|D);再根据谜底的长度L,从字典排除掉长度不是L的单词。
    </li>
    <br/>
    <li>
         第一次猜字母E,如果反馈中包含字母E,则获取E在谜底的位置索引数组,先排除掉字典中不含有字母E的单词，
         再排除掉单词中出现E的次数不等于E的位置索引数组的大小的单词,最后排除掉单词中E出现的位置不是E在谜底的索引数组中的单词;
         如果反馈中不包含字母E,则排除掉字典中包含字母E的单词。
    </li>
    <br/>
    <li>
        总计当前字典中所剩下的单词中字母出现的次数,字母作为key,次数作为value保存在Map中,根据value降序排列Map,如果Map的keySet.size()>0,则取第一个key对应字母作为下次猜的字母;
        如果Map的keySet.size()=0,则采用策略2:使用字母词频顺序集合减去guessHistory中出现字母，取第一个作为下次猜的字母。
    </li>
    <br/>
    <li>
        循环第3步，直到猜新词再循环第2~4步。
    </li>
    <br/>
    <li>
        猜词猜到第80个，猜完就查询结果提交。
    </li>

 </ol>
 */
public class HangmanPlayer {

    // 出现的频率由高到低排列的字母集合
    private static List<String> lettersByFrequency = new CopyOnWriteArrayList<>();
    // 猜的所有单词集合
    private static List<String> words;
    // 根据单词长度分类的所有字典集合
    private static Map<String, List<String>> allDictionary = new HashMap<>();
    // 猜测记录
    private static List<String> guessHistory = new ArrayList<>();
    // 错误记录
    private static List<String> wrongHistory = new ArrayList<>();
    // 正确的猜词记录Map(key为猜对的字母,value为猜对的字母在"谜底"中的索引数组)
    private static Map<String, List<Integer>> rightGuess = new LinkedHashMap<>();
    // 候选字典Map(key为候选字母，value为候选字母统计的总次数)
    private static Map<Character, Integer> characters = new HashMap<>();
    private static StringBuffer sb = new StringBuffer();
    // 游戏规则定义的常量
    private static final String[] ACTIONS = {"startGame", "nextWord", "guessWord", "getResult", "submitResult"};
    private static final String SERVER_URL = "https://strikingly-hangman.herokuapp.com/game/on";
    private static final String PLAYER_ID = "1187688895@qq.com";
    private static final int NUMBER_OF_WORDS_TO_GUESS = 80;
    private static final int NUMBER_OF_GUESS_ALLOWED_FOR_EACH_WORD = 10;
    // 第一次猜的字母(根据wiki,字母E的概率最高)
    private static final String FIRST_GUESS_LETTER = "E";

    static {
        // 初始化单词库
        File file = new File("words.txt");
        try {
            words = FileUtils.readLines(file, "utf-8");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void initLetter() {
        for (char c : "EAOIUTSRNHLDCMFPGWYBVKXJQZ".toCharArray()) {
            lettersByFrequency.add(String.valueOf(c));
        }
    }

    private static void initDictionary() {
        // 初始化4级字典：A,B,C,D(难度由易到难)
        List<String> ADictionary = new CopyOnWriteArrayList<>();
        List<String> BDictionary = new CopyOnWriteArrayList<>();
        List<String> CDictionary = new CopyOnWriteArrayList<>();
        List<String> DDictionary = new CopyOnWriteArrayList<>();
        for (String word : words) {
            if (word.length() <= 5) {
                ADictionary.add(word);
            } else if (word.length() <= 8) {
                BDictionary.add(word);
            } else if (word.length() <= 12) {
                CDictionary.add(word);
            } else {
                DDictionary.add(word);
            }
        }
        allDictionary.put("A", ADictionary);
        allDictionary.put("B", BDictionary);
        allDictionary.put("C", CDictionary);
        allDictionary.put("D", DDictionary);
    }

    public static void main(String[] args) {

        // 请求参数Map
        Map<String, String> params = new HashMap<>();
        params.put("playerId", PLAYER_ID);
        // 初始化的动作为startGame
        params.put("action", ACTIONS[0]);
        CloseableHttpClient httpclient = null;
        try{
            // 创建客户端模拟Post请求
            httpclient = HttpClients.createDefault();
            HttpPost httpPost = new HttpPost(SERVER_URL);
            RequestConfig requestConfig = RequestConfig.custom()
                    .setSocketTimeout(3000)
                    .setConnectTimeout(3000)
                    .build();
            httpPost.setConfig(requestConfig);

            // 服务器响应内容
            Map<String, Object> content = new HashMap<>();

            // json的解析和封装对象
            ObjectMapper mapper = new ObjectMapper();

            // 是否继续玩游戏
            boolean work = true;

            while (work){
                content = perform(mapper, params, httpclient, httpPost, content);
                if (content.get("work") != null) {
                    work = (boolean) content.get("work");
                }
            }
        } catch (Exception e){
            e.printStackTrace();
        } finally {
            try {
                httpclient.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    /**
     * 执行程序的核心方法
     * @param mapper json的解析和封装对象
     * @param params 请求参数
     * @param httpclient  http客户端
     * @param httpPost 封装了Post请求的对象
     * @param content 服务器响应内容
     * @return 服务器响应内容
     */
    private static Map<String, Object> perform(ObjectMapper mapper, Map<String, String> params, CloseableHttpClient httpclient, HttpPost httpPost, Map<String, Object> content) {
        String requestParam, result;
        try {
            requestParam = mapper.writeValueAsString(params);
            StringEntity stringEntity = new StringEntity(requestParam, "utf-8");
            httpPost.setEntity(stringEntity);

            CloseableHttpResponse response = httpclient.execute(httpPost);
            int statusCode = response.getStatusLine().getStatusCode();
            // 如果服务器响应错误，重新执行请求
            while (statusCode != 200) {
                response = httpclient.execute(httpPost);
            }
            result = EntityUtils.toString(response.getEntity(), "utf-8");

            // 解析服务器响应内容 (注意是动态的,获取其中的值，需要在此覆盖之前)
            content = mapper.readValue(result, Map.class);

            // 请求的动作
            String requestAction = params.get("action");
            if ("startGame".equals(requestAction)) { //1.开始游戏的响应
                // 获取并保存响应参数
                String sessionId = content.get("sessionId").toString();
                content.put("sessionId", sessionId);

                // 更新请求参数
                updateRequestParams(params, sessionId, ACTIONS[1], null);
            } else if ("nextWord".equals(requestAction)) { // 2.请求单词的响应
                // 获取并保存响应参数
                Map<String,Object> responseParam = fetchAndSaveResponseParam(content);
                String sessionId = responseParam.get("sessionId").toString();

                // 第一次猜的字母
                String guess = FIRST_GUESS_LETTER;
                guessHistory.add(guess);

                // 更新请求参数
                updateRequestParams(params, sessionId, ACTIONS[2], guess);

                // 初始化字母
                initLetter();
                // 初始化字典
                initDictionary();
            } else if ("guessWord".equals(requestAction)) { // 3.猜一个字母的响应
                // 获取并保存响应参数
                Map<String,Object> responseParam = fetchAndSaveResponseParam(content);
                String sessionId = responseParam.get("sessionId").toString();
                String word = responseParam.get("word").toString();
                int totalWordCount = Integer.parseInt(responseParam.get("totalWordCount").toString());
                int wrongGuessCountOfCurrentWord = Integer.parseInt(responseParam.get("wrongGuessCountOfCurrentWord").toString());

                String guess = null;
                String action;

                // 每次猜新词都需要重新初始化allDictionary
                List<String> suitableDictionary = findDictionaryByTotalWordCount(totalWordCount);

                // 获取上一次猜的字母
                String lastGuess = guessHistory.get(guessHistory.size() == 0 ? 0 : guessHistory.size() - 1);

                // 判断上一次猜的历史记录是否命中
                if (!word.contains(lastGuess)) {
                    // 没有命中将上一次猜的字母添加到错误的历史记录中
                    wrongHistory.add(lastGuess);
                } else {
                    // 猜中则获取猜中的字母在谜底中的位置索引数组
                    recordRightGuess(word, lastGuess);
                }

                // 从候选字典中移除掉包含最新的错误历史中字母的单词
                if (wrongHistory.size() > 0) {
                    removeLatestWrongGuess(word, suitableDictionary);
                }

                // 根据最新的正确的猜词记录字母和索引位置，将进一步缩小候选字典：
                if (rightGuess.keySet().size() > 0) {
                    narrowDictionaryByRightGuess(suitableDictionary);
                }

                // 如果当前单词还没有猜完
                if (word.contains("*")) {
                    // 当前词猜错次数为小于10
                    if (wrongGuessCountOfCurrentWord < NUMBER_OF_GUESS_ALLOWED_FOR_EACH_WORD) {

                        if(suitableDictionary.size() > 0){
                            // 候选字典中每个字母出现的次数
                            countCharacterByLetter(suitableDictionary);
                        }

                        // 根据字母出现的次数降序排列
                        Map<Character, Integer> sortedCharacterMap = sortMapByValue(characters);
                        // (1)取第一个频率最高的字母作为下次猜的字母
                        if (sortedCharacterMap.keySet().size() > 0) {
                            Object[] guessArr = sortedCharacterMap.keySet().toArray();
                            // 猜字母策略：每次取最高频率的：
                            guess = guessArr[0].toString();
                            guessHistory.add(guess);
                        } else {
                            // (2)新的策略————先元音后辅音 (a)E->A->O->I->U;(b)T-S-R-N-H-L-D-C-M-F-P-G-W-Y-B-V-K-X-J-Q-Z
                            // 移除掉猜过的字母
                            lettersByFrequency.removeAll(guessHistory);
                            // 取剩下字母中的第一个
                            guess = lettersByFrequency.get(0);
                            guessHistory.add(guess);
                        }
                        action = ACTIONS[2];
                    } else {
                        // 当前次错误次数达到10次，根据当前总共猜的个数决定Action
                        action = createActionByTotalWordCount(totalWordCount);
                    }
                } else {
                    // 如果当前单词正确猜出来了,action也有2种可能：猜新词还是查询结果
                    action = createActionByTotalWordCount(totalWordCount);
                }

                // 更新请求参数
                updateRequestParams(params, sessionId, action, guess);
            } else if ("getResult".equals(requestAction)) { // 4.获取结果的响应
                // 获取并保存响应参数
                Map<String,Object> responseParam = fetchAndSaveResponseParam(content);
                String sessionId = responseParam.get("sessionId").toString();
                int totalWordCount = Integer.parseInt(responseParam.get("totalWordCount").toString());

                // 根据响应，准备请求参数
                String action = totalWordCount == 80 ? ACTIONS[4] : ACTIONS[1];

                // 更新请求参数
                updateRequestParams(params, sessionId, action, null);
            } else { // 5.提交结果的响应
                // 获取并保存响应参数
                String sessionId = fetchAndSaveResponseParam(content).get("sessionId").toString();

                // 更新请求参数
                updateRequestParams(params, sessionId, ACTIONS[4], null);

                // 结束游戏
                content.put("work", false);

                // 输出记录
                printResult(content);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return content;
    }

    /**
     * 打印结果
     * @param content 响应内容
     */
    private static void printResult(Map<String, Object> content) {
        System.out.println("Final Score: " + content.get("score"));
    }

    /**
     * 统计候选字典中每个字母出现的总次数
     * @param suitableDictionary 候选字典
     */
    private static void countCharacterByLetter(List<String> suitableDictionary) {
        characters.clear();
        // 每次先清空
        sb.setLength(0);
        sb.append(suitableDictionary);
        String letters = sb.toString().replace(" ","").replace("[","").replace("]","").replace(",","");
        char[] chars = letters.toCharArray();
        for (char c : chars) {
            // 排除掉已经猜对的字母
            if (!rightGuess.keySet().contains(String.valueOf(c))) {
                characters.merge(c, 1, (a, b) -> a + b);
            }
        }

    }

    /**
     * 根据正确的猜词记录窄化候选字典
     * @param suitableDictionary 候选字典
     * @throws NoSuchFieldException 没有这个字段的异常
     * @throws IllegalAccessException 非法访问异常
     */
    private static void narrowDictionaryByRightGuess(List<String> suitableDictionary) throws NoSuchFieldException, IllegalAccessException {
        // 使用反射获取Map最后插入的记录,时间复杂度最低：o(1)
        Field tail = rightGuess.getClass().getDeclaredField("tail");
        tail.setAccessible(true);
        Map.Entry<String, List<Integer>> me = (Map.Entry<String, List<Integer>>) tail.get(rightGuess);
        // 正确的字母
        String key = me.getKey();
        // 正确的字母在谜底中的位置索引数组
        List<Integer> values = me.getValue();
        for (String s : suitableDictionary) {
            for (Integer index : values) {
                // 删除掉指定位置不是所猜的正确字母的候选词 + 字母key在单词s中出现的次数!= values.size()
                try {
                    if (!String.valueOf(s.charAt(index)).equals(key) || countTotalTimes(key, s) != values.size()) {
                        suitableDictionary.remove(s);
                    }
                } catch (StringIndexOutOfBoundsException e) {
                    suitableDictionary.remove(s);
                }
            }
        }
    }

    /**
     * (从候选字典中)移除最新的错误单词
     * @param word 谜底单词
     * @param suitableDictionary 候选字典
     */
    private static void removeLatestWrongGuess(String word, List<String> suitableDictionary) {
        String wrong = wrongHistory.get(wrongHistory.size() - 1);
        for (String s : suitableDictionary) {
            // 排除掉单词中含有错误单词或者字母个数不等于谜底个数的候选词
            if (s.contains(wrong) || s.length() != word.length()) {
                suitableDictionary.remove(s);
            }
        }
    }

    /**
     * 记录正确的猜词记录
     * @param word 谜底单词
     * @param lastGuess 最近猜的字母
     */
    private static void recordRightGuess(String word, String lastGuess) {
        char[] chars = word.toCharArray();
        // 正确的字母在谜底中的位置索引数组
        List<Integer> indexes = new ArrayList<>();
        int index = 0;
        for (char c : chars) {
            // 排除掉为*的字符
            String strVal = String.valueOf(c);
            if (!strVal.equals("*")) {
                if (strVal.equals(lastGuess)) {
                    indexes.add(index);
                }
            }
            index++;
        }
        // 将猜正确的字母，以key-value形式保存到正确猜的Map中
        rightGuess.put(lastGuess, indexes);
    }

    /**
     * 更新请求参数
     *
     * @param params    请求参数Map
     * @param sessionId 会话id
     * @param action    请求动作
     * @param guess     猜的字母
     */
    private static void updateRequestParams(Map<String, String> params, String sessionId, String action, String guess) {
        params.clear();
        params.put("sessionId", sessionId);
        params.put("action", action);
        params.put("guess", guess);
    }

    /**
     * 获取并保存响应参数
     * @param content 响应参数内容
     * @return 响应的map
     */
    private static Map<String,Object> fetchAndSaveResponseParam(Map<String, Object> content) {
        String playerId,datetime,word;
        int wrongGuessCountOfCurrentWord,correctWordCount,totalWrongGuessCount,score;
        Map data = (Map) content.get("data");

        if(data.get("word") != null){
            word = data.get("word").toString();
            content.put("word",word);
        }
        if(data.get("playerId") != null){
            playerId = data.get("playerId").toString();
            content.put("playerId", playerId);
        }
        if(data.get("correctWordCount") != null){
            correctWordCount = Integer.parseInt(data.get("correctWordCount").toString());
            content.put("correctWordCount", correctWordCount);
        }
        if(data.get("score") != null){
            score = Integer.parseInt(data.get("score").toString());
            content.put("score", score);
        }
        if(data.get("totalWrongGuessCount") != null){
            totalWrongGuessCount = Integer.parseInt(data.get("totalWrongGuessCount").toString());
            content.put("totalWrongGuessCount", totalWrongGuessCount);
        }
        if(data.get("datetime") != null){
            datetime = data.get("datetime").toString();
            content.put("datetime", datetime);
        }
        if(data.get("wrongGuessCountOfCurrentWord") != null){
            wrongGuessCountOfCurrentWord = Integer.parseInt(data.get("wrongGuessCountOfCurrentWord").toString());
            content.put("wrongGuessCountOfCurrentWord", wrongGuessCountOfCurrentWord);
        }

        String sessionId = content.get("sessionId").toString();
        int totalWordCount = Integer.parseInt(data.get("totalWordCount").toString());
        content.put("sessionId", sessionId);
        content.put("totalWordCount", totalWordCount);

        return content;
    }

    /**
     * 根据当前猜的总单词个数生成请求Action
     *
     * @param totalWordCount 当前猜的总词数
     * @return Action ("nextWord" || "getResult")
     */
    private static String createActionByTotalWordCount(Integer totalWordCount) {
        String action;
        if (totalWordCount >= NUMBER_OF_WORDS_TO_GUESS) {
            // 猜到了第80个单词，就不再猜了，拿到结果
            action = ACTIONS[3];
        } else {
            // 不到第80个单词，猜下个词
            action = ACTIONS[1];
            initLetter();
            initGuessStatus();
        }
        return action;
    }

    /**
     * 初始化猜词状态：清空每次猜新词时所需要的猜词记录、错误记录和正确记录
     */
    private static void initGuessStatus() {
        guessHistory.clear();
        wrongHistory.clear();
        rightGuess.clear();
    }

    /**
     * 统计字母key在单词s中出现的总次数
     *
     * @param key 字母
     * @param s   单词
     * @return 统计字母在单词中出现的总次数
     */
    private static int countTotalTimes(String key, String s) {
        int result = 0;
        for (int i = 0; i < s.toCharArray().length; i++) {
            if (String.valueOf(s.toCharArray()[i]).equals(key)) {
                result++;
            }
        }
        return result;
    }

    /**
     * 按照Map的value降序排列,即次数最高的字母排在最前面
     *
     * @param characters 待排序的map
     * @return 排序后的Map
     */
    private static Map<Character, Integer> sortMapByValue(Map<Character, Integer> characters) {
        Map<Character, Integer> sortedMap = new LinkedHashMap<>();
        if (characters != null && !characters.isEmpty()) {
            List<Map.Entry<Character, Integer>> entryList = new ArrayList<>(characters.entrySet());
            entryList.sort((o1, o2) -> o2.getValue() - o1.getValue());
            Iterator<Map.Entry<Character, Integer>> iterator = entryList.iterator();
            Map.Entry<Character, Integer> tempEntry;
            while (iterator.hasNext()) {
                tempEntry = iterator.next();
                sortedMap.put(tempEntry.getKey(), tempEntry.getValue());
            }
        }
        return sortedMap;
    }

    /**
     * 根据当前第几个单词找到对应的字典
     *
     * @param totalWordCount 当前第几个单词
     * @return 当前单词找到对应的字典
     */
    private static List<String> findDictionaryByTotalWordCount(Integer totalWordCount) {
        List<String> dictionary;
        String difficulty;
        if (totalWordCount <= 20) {
            difficulty = "A";
        } else if (totalWordCount <= 40 && totalWordCount > 20) {
            difficulty = "B";
        } else if (totalWordCount <= 60 && totalWordCount > 40) {
            difficulty = "C";
        } else {
            difficulty = "D";
        }
        dictionary = allDictionary.get(difficulty);
        return dictionary;
    }
}
