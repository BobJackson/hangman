# hangman
Hangman game which to guess a word .

### 一、游戏背景
Hangman在西方是一个家喻户晓的猜词游戏。Hang的英文意思是“绞死”，而Man的英文意思是“人”。由于竞猜者在规定的猜词次数内没有猜中单词就认为被“绞死”，Hangman便由此而得名。该游戏可以训练竞猜者的反应能力，又可以测试其词汇量。西方在电视节目中经常利用该游戏进行有奖竞猜。如果没有记错的话，中国也有"七步诗"之说，也就是对应的"规定的次数之内"，不然就绞死了。


### 二、游戏规则
组织者暗中写出一个英语单词，不公布于众，只是告诉该词有多少个字母，并按字母的多少给出空格，然后让大家猜。竞猜者一次猜一个字母，每猜中一个字母主持人就把该字母按它在被猜词中的顺序填写到空格中。如果该词有两个(或两个以上)相同的字母，当该字母被猜中时，组织者就按字母在被猜单词中的顺序全部写出。为了限制猜词的次数，组织者画一个“绞刑架”，如果被猜的单词中没有竞猜者所猜的字母，则视为一次没猜中，组织者就在“绞刑架”下画出小人儿身体的一个部分，当小人儿被画完整的时候，竞猜者就被“绞死”了，即竞猜者就输了该次游戏。先可以亲自体验一下[hangman](http://www.hangman.no/)在线游戏。  
  
我也文字描述一个例子:组织者暗中写下hello这个单词，给出5个空格，并告诉竞猜者该词有五个字母。竞猜者首先可能猜有字母A，主持人告诉他／她所猜单词中没有A，并在“绞刑架”下面画一个小人儿的头；然后竞猜者继续猜有字母H，主持人就告诉他／她所猜的单词中有一个H，并按该字母在单词中的顺序填写空格，即 H _ _ _ _;竞猜者再猜有字母U，由于所猜词中没有U，主持人便又在“绞刑架”下画一个小人儿的躯干；竞猜者继续猜有字母L，主持人告诉大家有两个L，并填写在空格上 H _ LL _;这时可能有竞赛者会猜中是hello，那么他／她就赢了这次游戏。如果没猜中，就再在“绞刑架”下面画小人儿的腿、胳膊、手和脚。当画成整个小人儿的时候，竞猜者就输了游戏。


### 三、服务端定义的规则
参考Strikingly的面试题：[Strikingly Interview Test](https://github.com/joycehan/strikingly-interview-test-instructions/tree/new)


### 四、个人解读hangman
服务器给出的规则很清晰：
* 一共猜80个单词，每个单词最多允许猜错10次，猜对一个单词20分，猜错一次扣1分。<br/><br/>

* 允许跳过当前词不猜，其实最好是不要跳过，因为当前定义的"游戏规则"————分数制，平均分是1000分，给出的示例中最高分达到了1307分，这么多分是怎么来的，我觉得应该是没有随意跳过当前词，因为就算猜错了9次，第十次猜对，你也是赚了分，这一点很重要，关系到程序的逻辑。(我们可以假想一下,如果是最后一个词了，当前得分1000分，放弃一个词,就保底1000分,如果猜下去可能10次后还是猜错，那么990分，如果10次猜对了1011分,显然你已经超过平均水平了，可以安心提交你的程序代码了,毕竟1000分就像60分万万岁一样。这步险棋你值得拥有！后来我结合程序想想也是，放弃就是不去争取20分，因为你跳过当前词，totalWordCount++,它并不会真心"白白"生成同等难度的词给你滴。还有一点，越到后面，单词越长，你猜错的概率会很小的，如果你的单词库准备的很充足的话。在刚开始的地方，所谓"难度较低的"前20个单词中，你就更不应该随意跳过了，后面我再分析。)<br/><br/>

* 这个Hangman自动猜词,是靠的词库和"算法"，你试过就知道服务器生成的词，有很多是你根本想不到的，所以有一个"足够大"的词库，超级重要。我用事实来证明一下，我第一次构建的words.txt是参考 [Java实现HangMan自动解题程序---初涉AI](http://blog.csdn.net/china_zoujinyong/article/details/26977091)
,也是用jsoup爬的4级单词，大约4600个，后来我找到了6级的词汇，大约8000个，此时分数提高了,再后来我找到6万的单词库，此时量变产生了质变，分数一下就提升了很多了，当然离1000分，还有点距离，最高900多分，程序平均分700分左右，用时大约15分钟；然后我就进一步确定了词库量的重要性，进过Google,百度，一顿搜索，终于找到了46万的单词库，再跑一次程序，我一边温故一下《权力的游戏》，一边等着，结果大约1个半多小时过去了，86分钟，程序跑完了，分数第一次让我喜出忘外，1326分，{"message":"GAME OVER","sessionId":"3d3fe9c64d98d01a0be091ef28a8bd7b","data":{"playerId":"1187688895@qq.com","sessionId":"3d3fe9c64d98d01a0be091ef28a8bd7b","totalWordCount":80,"correctWordCount":78,"totalWrongGuessCount":234,"score":1326,"datetime":"2017-11-08 04:03:43"}}<br/><br/>

* 前面讲到了得分制、不要轻易跳过、词库量的重要性，还有几点伏笔：(1)当前词的序号totalWordCount与难度等级问题; (2)算法，什么样的算法才能取得一个更好的得分。先说难度问题，显然常识很容易"骗"你，单词越短，你越好猜，其实呢？单词越短越难猜，我不是指1~2的字母的单词，游戏规则中基本不会出现，最低3个单词，在后面的猜词过程中，发现就是单词越短难度越难。当时我的第一个反应也是和做过这个面试题的人同样的感受，要"优化"：[Node.js 自动猜单词程序的实现](https://segmentfault.com/a/1190000002717701),一个优化就是使用正则表达式，另一个就是更高级的通过单词的前缀和后缀来来辅助猜词(后来想想这主要是针对长一点的单词的，短的单词也是没有太大用的)。后来我就思索了一下我的算法。算法的本质就是在有限步骤中一步一步去解决问题的，说到底没有什么特别高级的，也可能是我算法理解地比较肤浅吧。我的思路就是最大排除法：尽可能去排除可能性最大的字母，这样猜对的概率就越大。下面是我写在HangmanPlayer.java中的描述<br/><br/>
 >HangmanPlayer核心算法描述:<br/>
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
            <li>(2)字母词频顺序集合:EAOIUTSRNHLDCMFPGWYBVKXJQZ</li><br/>
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
<br/><br/>
我说下我当时为什么使用最大排除法，如果那个Node.js 实现的程序思路，就是动态构建正则表达式去匹配和筛选，但是最后还是要猜的，想想还是不好猜，还有一点正则表达式是Java中比较高级的知识点，阅读难度有点高，不清晰，程序的可读性较差(把难懂说这么清晰脱俗，还是可以的，嘿嘿)，直接使用概率论的知识易懂，程序也易读，程序逻辑也清晰。还有一点就是我想的————怎样提高程序猜对的概率，随机猜显然是不合适的，英语单词的构建还是有一定规则的(也就是为什么很容易想到构词法，前缀，后缀等英语知识)，在汇总的层面上，还有一点字母的统计后有一定的规律，就使用量最多的字母有一个相对的排序，也就是我使用策略2:EAOIUTSRNHLDCMFPGWYBVKXJQZ, 也容易想清楚英语中就5个元音，其余的21个都是辅音，所以元音在前辅音在后，这是大体规律，还有一点就是翻过英语词典都知道T,S,R那几个字母开头的单词不少吧。<br/><br/>

*理解这么多，差不多就可以理理思路写程序了，上面是一个客户端的代码，也就是HangmanPlayer.java是去调用服务器端的api完成与服务器的交互，为了报密strikingly的服务器端API接口，我把请求的url换成了我自己网站的，现在还没有实现，不过我会尽快写出服务器端的代码，毕竟做一些有挑战的事，我还是愿意的。
我描述下HangmanPlayer.java中大致涉及到的知识点。
1. Maven工程，使用到的依赖:commons-io、httpclient、jackson-databind,分别完成文件的读取，模拟post请求,处理json数据;
2. Java 1.8 中lambda 表达式:来用集合的排序和集合元素的统计;
3. 反射，取LinkedHashSet的最后加入的元素，不直接遍历，降低程序的时间复杂度。
4. 关于程序的异常的处理，我的处理方式还是有些问题的，异常发生可能会在这一步CloseableHttpResponse response = httpclient.execute(httpPost);所以不能通过while (statusCode != 200){response = httpclient.execute(httpPost);}处理，《Java Network编程》又可以收入囊中了。
5. 最后一点，这个程序是没有写成多线程的，但是总体的代码规范性：变量命名,方法名，程序逻辑结构在我自己这一关还是过得去的，我也重构过程序中很多的代码，所以代码的冗余会比较少。又要感谢一波Git和IDEA工具了。

文章的最后，发句感慨：我们总是站在巨人的肩膀上编程，感谢有你们的付出！






