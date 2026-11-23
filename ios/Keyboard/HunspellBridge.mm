#import "HunspellBridge.h"
#import "hunspell.hxx"

@implementation HunspellBridge {
    Hunspell *_hunspell;
}

- (nullable instancetype)initWithAffPath:(NSString *)affPath dicPath:(NSString *)dicPath {
    self = [super init];
    if (self) {
        _hunspell = new Hunspell(affPath.UTF8String, dicPath.UTF8String);
        if (!_hunspell) {
            return nil;
        }
    }
    return self;
}

- (void)dealloc {
    delete _hunspell;
}

- (BOOL)spell:(NSString *)word {
    return _hunspell->spell(word.UTF8String) != 0;
}

- (NSArray<NSString *> *)suggest:(NSString *)word max:(NSInteger)maxCount {
    char **list = nullptr;
    const int count = _hunspell->suggest(&list, word.UTF8String);
    NSMutableArray<NSString *> *result = [NSMutableArray array];
    const long limit = MIN((long)count, (long)MAX(maxCount, 0));
    for (long i = 0; i < limit; i++) {
        NSString *candidate = list[i] ? @(list[i]) : nil;
        if (candidate) {
            [result addObject:candidate];
        }
    }
    _hunspell->free_list(&list, count);
    return result;
}

- (void)addWord:(NSString *)word {
    _hunspell->add(word.UTF8String);
}

@end
