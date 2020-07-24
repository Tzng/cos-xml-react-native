import * as React from 'react';
import { 
  SafeAreaView,
  StyleSheet,
  ScrollView,
  StatusBar,
  View, 
  Text, 
  Button, 
  Image, 
  ImageSourcePropType
 } from 'react-native';

import {
  Header,
  Colors,
} from 'react-native/Libraries/NewAppScreen';

import ImagePicker from 'react-native-image-picker';
import * as Progress from 'react-native-progress';

import CosXmlReactNative, { UploadObjectRequest } from 'cos-xml-react-native';

const placeholder = require('../image/placeholder-image.png')

const SECRET_ID: string = 'Your Secret ID'  // 腾讯云永久密钥 SecretID
const SECRET_KEY: string = 'Your Secret Key'  // 腾讯云永久密钥 SecretKey
const USE_SESSION_TOKEN_CREDENTIAL: boolean = true  // 是否使用临时密钥请求 COS
const STS_URL: string = 'http://127.0.0.1:3000/sts' // STS 密钥服务器地址
// const STS_URL: string = 'http://10.0.2.2:3000/sts' // STS 密钥服务器地址

const region: string = 'ap-guangzhou' // 存储桶所在地域
const bucket: string = 'bucket-4-csharp-obj-test-1253653367'  // 存储桶
const objectKey: string = 'rn-example-object' // 上传对象键

let uploadRequest: UploadObjectRequest | null;

export default function App() {
  const [result, setResult] = React.useState<string | undefined>();
  const [progress, setProgress] = React.useState<number | undefined>();
  const [imageSource, setSource] = React.useState<object | undefined>();

  React.useEffect(() => {
    if (USE_SESSION_TOKEN_CREDENTIAL) {
      // 使用临时密钥初始化
      CosXmlReactNative.initWithSessionCredentialCallback({
        region: region
      }, async () => {
        // 请求临时密钥
        const response = await fetch(STS_URL);
        const responseJson = await response.json();
        const credentials = responseJson.credentials;
        const expiredTime = responseJson.expiredTime;
        const sessionCredentials = {
          tmpSecretId: credentials.tmpSecretId,
          tmpSecretKey: credentials.tmpSecretKey,
          expiredTime: expiredTime,
          sessionToken: credentials.sessionToken
        };
        console.log(sessionCredentials);
        return sessionCredentials;
      })
    } else {
      // 使用永久密钥进行本地调试
      CosXmlReactNative.initWithPlainSecret({
        region: region
      }, {
        secretId: SECRET_ID,
        secretKey: SECRET_KEY
      })
    }
  }, []);

  function onPickImage() {
    const options = {
      title: 'Select Avatar',
      storageOptions: {
        skipBackup: true,
        path: 'images',
      },
    };

    ImagePicker.showImagePicker(options, (response) => {
      if (response.didCancel) {
        console.log('User cancelled image picker');
      } else if (response.error) {
        console.log('ImagePicker Error: ', response.error);
      } else {
        console.log(response.uri)
        setProgress(0)
        setResult('')
        setSource(placeholder)

        uploadRequest = {
          bucket: bucket,
          object: objectKey,
          // 对象本地 Uri
          fileUri: response.uri
        }
        doUpload()
      }
    });
  }

  function pauseUpload() {
    if (uploadRequest) {
      // 暂停上传
      CosXmlReactNative.pause(uploadRequest)
    }
  }

  function doUpload() {
    if (uploadRequest) {
      // 上传、续传对象
      CosXmlReactNative.upload(uploadRequest, 
        (processedBytes: number, targetBytes: number) => {
          // 回调进度
          console.log('put Progress callback : ', processedBytes, targetBytes)
          setProgress(processedBytes / targetBytes)
      }).then((info) => {
        // info 包含上传结果
        console.log(info)
        setSource({uri: uploadRequest?.fileUri})
        setResult(JSON.stringify(info))
        uploadRequest = null
      }).catch((e) => {
        console.log(e)
        setResult(e.code + ',' + e.message)
      })
    }
  }

  function downloadImage() {
    setProgress(0)
    setResult('')
    setSource(placeholder)

    // 下载对象
    CosXmlReactNative.download({
      bucket: bucket,
      object: objectKey
    }, (processedBytes: number, targetBytes: number) => {
      // 回调进度
      console.log('get Progress callback : ', processedBytes, targetBytes)
      setProgress(processedBytes / targetBytes)
    }).then((filePath) => {
      // uri 是保存到本地的路径
      setSource({uri: "file://" + filePath})
      setResult(filePath)
    }).catch((e) => {
      console.log(e)
      setResult(e.code + ',' + e.message)
    })
  }

  return (
    <>
      <StatusBar barStyle="dark-content" />
      <SafeAreaView>
        <ScrollView
          contentInsetAdjustmentBehavior="automatic"
          alwaysBounceVertical={true}
          style={styles.scrollView}>
          <Header />
          <View style={styles.container}>
            <Text style={styles.title}>COS React Native Example</Text>
            <Image style={styles.img} source={imageSource != null ? imageSource as ImageSourcePropType: placeholder} />
            <View style={styles.button_container}>
              <Button
                onPress={onPickImage}
                title="上传图片"
                color="#841584"
              />
              <View style={{width: 10}} />
              <Button
                onPress={pauseUpload}
                title="暂停上传"
                color="#841584"
              />
              <View style={{width: 10}} />
              <Button
                onPress={doUpload}
                title="继续上传"
                color="#841584"
              />
              <View style={{width: 10}} />
              <Button
                onPress={downloadImage}
                title="下载"
                color="#841584"
              />
            </View>
            <View style={styles.result_container}>
              <Text style={{color: "#841584", fontSize: 20}} >Result: {"\n"}</Text>
              <Progress.Bar progress={progress} width={300} />
              <View style={{height: 20}} />
              <Text>{result}</Text>
              <View style={{height: 20}} />
            </View>
          </View>
        </ScrollView>
      </SafeAreaView>
    </>
  );
}

const styles = StyleSheet.create({
  scrollView: {
    backgroundColor: Colors.white,
  },
  engine: {
    position: 'absolute',
    right: 0,
  },
  body: {
    backgroundColor: Colors.white,
  },
  sectionContainer: {
    marginTop: 32,
    paddingHorizontal: 24,
  },
  sectionTitle: {
    fontSize: 24,
    fontWeight: '600',
    color: Colors.black,
  },
  sectionDescription: {
    marginTop: 8,
    fontSize: 18,
    fontWeight: '400',
    color: Colors.dark,
  },
  highlight: {
    fontWeight: '700',
  },
  footer: {
    color: Colors.dark,
    fontSize: 12,
    fontWeight: '600',
    padding: 4,
    paddingRight: 12,
    textAlign: 'right',
  },
  container: {
    alignItems: 'center',
    justifyContent: 'center',
    flex: 1
  },
  title: {
    height:80,
    textAlign: "center",
    textAlignVertical: "center",
    fontSize: 24,
    color: '#455a64'
  },
  button_container: {
    height: 60,
    flexDirection: "row",
    alignItems: 'center',
    justifyContent: 'center',
  },
  img: {
    resizeMode: "contain",
    backgroundColor: '#cfcfcf',
    height: 240,
    width: 240,
  },
  result_container: {
    alignItems: 'center',
    justifyContent: 'center',
    marginHorizontal: 20,
  }
});
