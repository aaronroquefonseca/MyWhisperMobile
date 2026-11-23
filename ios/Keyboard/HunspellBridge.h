#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Minimal ObjC facade over Hunspell (C++ stays out of Swift).
/// Threading: create and use each instance on one thread.
@interface HunspellBridge : NSObject

- (nullable instancetype)initWithAffPath:(NSString *)affPath
                                 dicPath:(NSString *)dicPath;
- (BOOL)spell:(NSString *)word;
- (NSArray<NSString *> *)suggest:(NSString *)word max:(NSInteger)maxCount;
- (void)addWord:(NSString *)word;

@end

NS_ASSUME_NONNULL_END
