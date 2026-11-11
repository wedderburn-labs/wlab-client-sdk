#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <TraccarClientSDK/TraccarClientSDK.h>

// WLAB: bring the tracker back as soon as the app launches, from its saved settings.
//
// iOS can end a tracking app in the background, then launch it again in the background when the
// device leaves its stationary region or moves a long way. That launch only helps if location
// managers are set up straight away. Waiting for JavaScript (sign-in, a server call) loses the event,
// and on 10 Nov 2026 the iPhone missed a whole drive this way. With no saved settings, or tracking
// off, this does nothing: the tracker comes back stopped.
@interface WlabLaunchRestore : NSObject
@end

@implementation WlabLaunchRestore

+ (void)load {
  [[NSNotificationCenter defaultCenter]
      addObserverForName:UIApplicationDidFinishLaunchingNotification
                  object:nil
                   queue:[NSOperationQueue mainQueue]
              usingBlock:^(NSNotification *note) {
                [TCSDKTrackerKt sharedTrackerWithCompletionHandler:^(TCSDKTracker *_Nullable tracker,
                                                                     NSError *_Nullable error) {
                  if (error != nil) {
                    NSLog(@"WLAB tracker restore failed: %@", error);
                  }
                }];
              }];
}

@end
