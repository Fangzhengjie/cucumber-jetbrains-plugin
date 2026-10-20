# language: zh-CN
功能: 中文步骤
  场景: 中文关键字与 Java 步骤
    假如 the java service is ready
    而且 the java user "erin" exists
    当 the java client sends 7 requests
    那么 the java total is 7

  场景：没有空格的关键字与 Kotlin 步骤
    假如the kotlin service is ready
    当the kotlin client sends 8 requests
    那么the kotlin result is "fine"
    但是 the java total is 7
