@This fork was created in the scope of a summer internhsip at Gympass

# Elasticsearch Redis Rescore Plugin

Ever wanted to use data from external systems to influence scoring in Elasticsearch? now you can.

The idea is simple: query a low-latency service per document during search and get a numeric value for that specific document, then use that number as a multiplier for influencing the document score, up or down.

This plugin uses Redis to rescore top ranking results in Elasticsearch. It's your job to make sure the query gets the basic filtering and scoring right. Then, you can use the plugin to [rescore](https://www.elastic.co/guide/en/elasticsearch/reference/7.5/search-request-body.html#request-body-search-rescore) the top N results.

Rescoring with this plugin assumes Redis contains keys that can be correlated with the data in the documents, such that for every doc D there exists a value in a predefined field that also exists in Redis as a key whose value is numeric.

Documents that do not contain this field, or no value in this field, or that value does not exist in Redis as a key - are left untouched.

See an example below.

## Installation

Follow the standard plugin installation instructions, with a zip version of the compiled plugin: [https://www.elastic.co/guide/en/elasticsearch/plugins/current/installation.html](https://www.elastic.co/guide/en/elasticsearch/plugins/current/installation.html)

## Usage

```json
{
  "query": { "match_all":  {} },
  "rescore": {
    "redis":{
      "key_field": "productId.keyword",
      "key_prefixes": ["mystore-","gympass-","gym-"],
      "score_weights": [0.5, 1.0],
      "score_functions":["null","pow(_,2)"],
      "score_operator": "ADD",
      "boost_operator": "ADD",
      "boost_weight": 1.0
    } 
  } 
}
```
In this example, we are expecting each hit to contain a field `productId` (of keyword type). The value of that field will be looked up in Redis as a key (for example, Redis key `mystore-abc123` will be looked-up for a document with productId abc123; the `mystore-` key prefix is configurable in query time).
The `score_operator` field is the operator you want to be using when doing your final rescore, you can use `ADD`, `MULTIPLY`, or `SUBTRACT`.

The value which will be found under that Redis key, if exists and of numeric type, will be multiplied by the current document score to produce a new score.

You can use `0` to demote results (e.g. mark as unavailable in stock), `1` to leave unchanged, or any other value to produce positive or negative boosts based on your business logic.

<br/>
<br/>

# Fields and how to use them

<br/>

## --> `key_prefixes` : str[]
A list that contains the `key_prefixes` to use

<br/>

## --> `score_weights` : float[]
A list that holds the weight value for each of the key_prefixes in the `key_prefixes` field.    
For example, in the **Usage** section, the `key_prefix` *"mystore-"* is beeing multiplied by **0.5** and the *"gympass-"* for **1**.

<br/>

## --> `boost_weight` : float
Holds the weight value of the **elasticsearch_score**.
For example, if the **elasticsearch_score** is **2** and the `boost_weight` **0.5** then the final **elasticsearch_score** will be **1**. 

<br/>

## --> `score_operator` | `boost_operator` : str
Can be:

- MULTIPLY
- ADD
- SET
- SUBTRACT

The formula is:

**score** = *elasticsearch_score* (`boost_operator`) ( redis[`key_prefix[0]`+`key_field`] (`score_operator`) redis[`key_prefix[n-1]`+`key_field`])


If `score_operator`: *"ADD"* **&** `boost_operator`: *"MULTIPLY"*,   
The formula will be:
```
score = elasticsearch_score * ( redis[mystore-20] + redis[gympass-20])
```
<br/>

## --> `score_functions` : str[]

A list that holds the functions to be applied to eatch value on redis, corresponding to the `key_prefixes`.

In the example we have **"null"** linked to `"mystore-"` and **"pow(_, 2)"** linked to `"gympass-"`, what does this mean?

Well **"null"** is the default value, so the `"mystore-"` field will return its normal value without any modification. On the other hand we have the `"gympass-"` field that is beeing afected by the **pow(_,2)** function, in this case the plugin will take the value stored in the redis data base with key `gympass-{productId.keyword}` and a apply it to the function given, where the arg `"_"` is.

In this case lets imagine that the stored value is *3*, so the rescore for that field will be `pow(3,2)` = **9**.

It's important to note that the `score_functions` field, just like the `score_weights`, uses indexes to work.


<br/>
<br/>

# Field Default Values
The only field that is **required** for the plugin to run is the `key_field`, all the others can be `null`.
## Default Values
### `score_operator` | `boost_operator`
***"ADD"***
<br/>

### `scores_weight` | `boost_weight` 
***1***
<br/>

### `score_functions`
***"null"***
<br/>
<br/>
<br/>

# Plugin Installer
<br/>

## 0- Dowload the plugin
If you dont have the **.zip** of the compiled plugin you need to download it.  
You can download it in the [release page](https://github.com/sdrrv/elasticsearch-rescore-redis/releases).

<br/>

## 1- Install the Plugin
Now you can `cd` into to the `bin` folder in your **ElasticSearch** instalation and run :
```bash
$ ./elasticsearch-plugin install file://dirOfYourPlugin
```
<br/>

## 2- Change the Redis host
By default the plugin will think that your **Redis** server is running in the `localhost:6379` , that beeing said, you can change the `Host` but not the `Port`.
The `Port` will always be `6379` because that's the default `Port` for the **Redis** server to run on. 
<br/>
To change the `Host`, you first need to go to the `config` folder in your **ElasticSearch** dir.
There open the `elasticsearch.yml` file, you want to add the following:

```YAML
redisRescore.redisUrl : "YourHostIP"
```

<br/>
<br/>

# Plugin Builder

## 1- First pull the git rep into your machine
<br/>

## 2- Install gradle in your machine

<p>In Linux you just have to do: </p>

```bash
$ sudo apt install gradle
```
<br/>

## 3- Install Java-JDK version 13
In order to compile the plugin, the Java version in your `$Path` must be <strong>version 13</strong> <br/>

If you are using the <strong>Intelij</strong> IDE then you can simply go to: 

> `settings` --> `Build, Execution, Deplyment` --> `Build Tools` --> `Gradle`

And in the `Gradle JVM` field, select the `Dowload JDK` option, there choose the **Version 13** and download.

<br/>

## 4- Install Docker and Redis
Once again, in order for the build to work you need to have the **Docker** installed in you machine, and the **Redis** in it. To do this we can simply run this commands in your terminal:<br/>

```bash
$ sudo apt install docker
$ sudo apt install docker-compose
$ sudo docker pull redis
```
<br/>

## 5- Build the Plugin
Finally, you are ready to build the Plugin.<br/>
You can run the gradle compiler by using in the project folder:
```bash
$ ./gradlew build
```
However, if you are using the <strong>Intelij</strong> IDE, you can simply run the `gradle build` task.

<br/>

## 6- Get the Plugin
After the successful build the compiled plugin will be under the `distributions` folder inside the `build` folder.
There will be 4 files in that dir, you want to take the `.zip` file.
