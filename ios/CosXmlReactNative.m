#import "CosXmlReactNative.h"
#import <QCloudCore/QCloudCore.h>
#import <QCloudcore/NSObject+QCloudModel.h>
#import <QCloudCOSXML/QCloudCOSXMLTransfer.h>
#import <QCloudCOSXML/QCloudCOSXMLDownloadObjectRequest.h>
#import <React/RCTConvert.h>

@interface CosXmlReactNative() <QCloudSignatureProvider, QCloudCredentailFenceQueueDelegate>

@property (nonatomic, strong) QCloudCredentailFenceQueue* credentialFenceQueue;

@end

NSString * const PROGRESS_EVENT = @"COSTransferProgressUpdate";
NSString * const UPDATE_CREDENTIAL_EVENT = @"COSUpdateSessionCredential";

@implementation CosXmlReactNative {
    dispatch_group_t g;
    QCloudCredential* sessionCredential;
    NSMutableDictionary<NSString*, QCloudCOSXMLUploadObjectRequest*> * requests;
    NSMutableDictionary<NSString*, NSData*> * resumeDataList;
    
    NSString* secretId;
    NSString* secretKey;
    
    BOOL hasObversers;
}

RCT_EXPORT_MODULE()

- (NSArray<NSString *> *)supportedEvents {
    return @[PROGRESS_EVENT, UPDATE_CREDENTIAL_EVENT];
}

/**
 用固定密钥初始化
 */
RCT_REMAP_METHOD(initWithPlainSecret, initWithConfig: (NSDictionary *)config plainSecret:(NSDictionary*)credential) {
    [self initService:config];
    
    secretId = [credential objectForKey:@"secretId"];
    secretKey = [credential objectForKey:@"secretKey"];
}

/**
 用临时密钥回调初始化
 */
RCT_REMAP_METHOD(initWithSessionCredentialCallback, initWithConfig:(NSDictionary*)config) {
    [self initService:config];
}

/**
 更新临时密钥
 */
RCT_EXPORT_METHOD(updateSessionCredential:(NSDictionary *)credentialInfo) {
    if (!sessionCredential) {
        sessionCredential = [QCloudCredential new];
    }
    
    sessionCredential.secretID = credentialInfo[@"tmpSecretId"];
    sessionCredential.secretKey = credentialInfo[@"tmpSecretKey"];
    sessionCredential.experationDate = [NSDate dateWithTimeIntervalSince1970:[RCTConvert int:credentialInfo[@"expiredTime"]]];
    sessionCredential.token = credentialInfo[@"sessionToken"];
    
    if (g) {
        dispatch_group_leave(g);
    }
}

RCT_EXPORT_METHOD(putObject:(NSDictionary *)options resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject) {
    NSString *requestId = [options objectForKey:@"requestId"];
    NSData *resumeData = [resumeDataList objectForKey:requestId];
    QCloudCOSXMLUploadObjectRequest* put;
    if (resumeData) {
        put = [QCloudCOSXMLUploadObjectRequest requestWithRequestData:resumeData];
        // remove resume data from cache
        [resumeDataList removeObjectForKey:requestId];
    } else {
        put = [QCloudCOSXMLUploadObjectRequest new];
        NSString* srcPath = [options objectForKey:@"fileUri"];
        if ([srcPath hasPrefix:@"file://"] || [srcPath hasPrefix:@"File://"]) {
            srcPath = [srcPath substringFromIndex:7];
        }
        put.object = [options objectForKey:@"object"];
        put.bucket = [options objectForKey:@"bucket"];
        put.body = [NSURL fileURLWithPath:srcPath];
    }
    
    [put setSendProcessBlock:^(int64_t bytesSent, int64_t totalBytesSent, int64_t totalBytesExpectedToSend) {
        NSLog(@"upload : totalSend %lld aim %lld", totalBytesSent, totalBytesExpectedToSend);
        if (self->hasObversers && requestId) {
            NSDictionary* info = @{@"requestId": requestId,
                                   @"processedBytes": @(totalBytesSent),
                                   @"targetBytes": @(totalBytesExpectedToSend)
                                   };
            dispatch_async(dispatch_get_main_queue(), ^{
                [self sendMessageToRN:info toChannel:PROGRESS_EVENT];
            });
        }
    }];
    [put setInitMultipleUploadFinishBlock:^(QCloudInitiateMultipartUploadResult * _Nullable multipleUploadInitResult, QCloudCOSXMLUploadObjectResumeData  _Nullable resumeData) {
            
    }];
    [put setFinishBlock:^(id outputObject, NSError* error) {
        // remove rquest from cache
        [self->requests removeObjectForKey:requestId];
        
        if (error) {
            reject([@(error.code) stringValue], error.localizedDescription, error);
        } else {
            NSMutableDictionary* dic = [NSMutableDictionary new];
            QCloudUploadObjectResult* result = (QCloudUploadObjectResult *) outputObject;
            [dic setObject:result.bucket forKey:@"Bucket"];
            [dic setObject:result.key forKey:@"Key"];
            [dic setObject:result.eTag forKey:@"ETag"];
            [dic setObject:result.location forKey:@"Location"];
            if (result.versionID) {
                [dic setObject:result.versionID forKey:@"VersionID"];
            }
            
            resolve([dic copy]);
        }
    }];
    
    // add request to cache
    [requests setObject:put forKey:requestId];
    [[QCloudCOSTransferMangerService defaultCOSTransferManager] UploadObject:put];
}

RCT_EXPORT_METHOD(pauseUpload:(NSString *)requestId resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject) {
    QCloudCOSXMLUploadObjectRequest* request = [requests objectForKey:requestId];
    if (request) {
        NSError *error;
        NSData* resumeData = [request cancelByProductingResumeData:&error];
        if (error) {
            reject([@(error.code) stringValue], error.localizedDescription, error);
        } else {
            // remove rquest from cache
            [requests removeObjectForKey:requestId];
            // add resume data to cache
            [resumeDataList setObject:resumeData forKey:requestId];
            resolve(@"");
        }
    } else {
        NSLog(@"No Request Found for %@", requestId);
    }
}

RCT_EXPORT_METHOD(getObject:(NSDictionary *)options resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject) {
    QCloudCOSXMLDownloadObjectRequest* request = [QCloudCOSXMLDownloadObjectRequest new];
    request.bucket = [options objectForKey:@"bucket"];
    request.object = [options objectForKey:@"object"];
    NSString* filePath = [options objectForKey:@"filePath"];
    if (!filePath) {
        NSString* dir = [NSTemporaryDirectory() stringByAppendingPathComponent:@"cos_download"];
        QCloudEnsurePathExist(dir);
        filePath = QCloudPathJoin(dir, request.object);
    }

    request.downloadingURL = [NSURL fileURLWithPath:filePath];
    
    [request setFinishBlock:^(id outputObject, NSError *error) {
        if (error) {
            reject([@(error.code) stringValue], error.localizedDescription, error);
        } else {
            resolve(filePath);
        }
    }];
    [request setDownProcessBlock:^(int64_t bytesDownload, int64_t totalBytesDownload, int64_t totalBytesExpectedToDownload) {
        NSLog(@"download : totalSend %lld aim %lld", totalBytesDownload, totalBytesExpectedToDownload);
        NSString *requestId = [options objectForKey:@"requestId"];
        if (self->hasObversers && requestId) {
            NSDictionary* info = @{@"requestId": requestId,
                                   @"processedBytes": @(totalBytesDownload),
                                   @"targetBytes": @(totalBytesExpectedToDownload)
                                   };
            dispatch_async(dispatch_get_main_queue(), ^{
                [self sendMessageToRN:info toChannel:PROGRESS_EVENT];
            });
        }
        
    }];
    [[QCloudCOSTransferMangerService defaultCOSTransferManager] DownloadObject:request];
}

RCT_EXPORT_METHOD(headObject:(NSDictionary*) options resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject) {
    QCloudHeadObjectRequest* headRequest = [QCloudHeadObjectRequest new];
    
    headRequest.bucket = [options objectForKey:@"bucket"];
    headRequest.object = [options objectForKey:@"object"];
    
    [headRequest setFinishBlock:^(NSDictionary* result, NSError* error) {
        if (error) {
            reject([@(error.code) stringValue], error.localizedDescription, error);
        } else {
            resolve(result);
        }
    }];
    
    [[QCloudCOSXMLService defaultCOSXML] HeadObject:headRequest];
}

- (void)startObserving {
    hasObversers = YES;
}

- (void)stopObserving {
    hasObversers = NO;
}

- (void) initService:(NSDictionary*)config {
    QCloudServiceConfiguration* configuration = [[QCloudServiceConfiguration alloc] init];
    configuration.signatureProvider = self;

    QCloudCOSXMLEndPoint* endpoint = [[QCloudCOSXMLEndPoint alloc] init];
    endpoint.regionName = [RCTConvert NSString:[config objectForKey:@"region"]];
    endpoint.useHTTPS = YES;
    configuration.endpoint = endpoint;

    [QCloudCOSXMLService registerDefaultCOSXMLWithConfiguration:configuration];
    [QCloudCOSTransferMangerService registerDefaultCOSTransferMangerWithConfiguration:configuration];

    self.credentialFenceQueue = [QCloudCredentailFenceQueue new];
    self.credentialFenceQueue.delegate = self;
    
    requests = [NSMutableDictionary new];
    resumeDataList = [NSMutableDictionary new];
}

-(void)sendMessageToRN:(NSDictionary*)info toChannel:(NSString*)channel {
    [self sendEventWithName:channel body:[info copy]];
}

- (void)fenceQueue:(QCloudCredentailFenceQueue *)queue requestCreatorWithContinue:(QCloudCredentailFenceQueueContinue)continueBlock {
    [self sendMessageToRN:[NSDictionary new] toChannel:UPDATE_CREDENTIAL_EVENT];
    g = dispatch_group_create();
    dispatch_group_enter(g);
    
    dispatch_group_notify(g, dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        if (self->sessionCredential && [self->sessionCredential.experationDate compare:NSDate.date] == NSOrderedDescending) {
            QCloudAuthentationV5Creator* creator = [[QCloudAuthentationV5Creator alloc] initWithCredential:self->sessionCredential];
            continueBlock(creator, nil);
        } else {
            continueBlock(nil, [NSError errorWithDomain:NSURLErrorDomain code:-1111 userInfo:@{NSLocalizedDescriptionKey:@"没有获取到临时密钥"}]);
        }
        self->g = nil;
    });
}

- (void) signatureWithFields:(QCloudSignatureFields*)fileds
                     request:(QCloudBizHTTPRequest*)request
                  urlRequest:(NSMutableURLRequest*)urlRequst
                   compelete:(QCloudHTTPAuthentationContinueBlock)continueBlock {
    if (secretKey && secretId) {
        // 使用永久秘钥
        QCloudCredential* credential = [QCloudCredential new];
        credential.secretID = secretId;
        credential.secretKey = secretKey;
        
        QCloudAuthentationV5Creator* creator = [[QCloudAuthentationV5Creator alloc] initWithCredential:credential];
        QCloudSignature* signature =  [creator signatureForData:urlRequst];
        continueBlock(signature, nil);
    } else {
        // 调用回调获取秘钥
        [self.credentialFenceQueue performAction:^(QCloudAuthentationCreator *creator, NSError *error) {
            if (error) {
                continueBlock(nil, error);
            } else {
                QCloudSignature* signature =  [creator signatureForData:urlRequst];
                continueBlock(signature, nil);
            }
        }];
    }
}

@end
