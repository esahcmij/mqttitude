//
//  mqttitudeLocationTVC.h
//  mqttitude
//
//  Created by Christoph Krey on 29.09.13.
//  Copyright (c) 2013 Christoph Krey. All rights reserved.
//

#import "CoreDataTableViewController.h"
#import "Friend+Create.h"
@interface mqttitudeLocationTVC : CoreDataTableViewController
@property (strong, nonatomic) Friend *friend;
@property (strong, nonatomic) Location *selectedLocation;

@end
