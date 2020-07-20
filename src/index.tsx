import { NativeModules } from 'react-native';

type CosXmlReactNativeType = {
  multiply(a: number, b: number): Promise<number>;
};

const { CosXmlReactNative } = NativeModules;

export default CosXmlReactNative as CosXmlReactNativeType;
