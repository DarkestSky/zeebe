/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */

import {screen, within} from '@testing-library/testcafe';
import {demoUser} from './utils/Roles';
import {config} from '../config';
import {setup, cmOperationIdField} from './Operations.setup';
import {DATE_REGEX} from './constants';
import {IS_NEW_FILTERS_FORM} from '../../src/modules/feature-flags';

fixture('Operations')
  .page(config.endpoint)
  .before(async (ctx) => {
    ctx.initialData = await setup();
  })
  .beforeEach(async (t) => {
    await t
      .useRole(demoUser)
      .maximizeWindow()
      .click(
        screen.queryByRole('link', {
          name: /view instances/i,
        })
      );
  });

test('infinite scrolling', async (t) => {
  await t.click(screen.queryByTitle('Expand Operations'));

  await t.expect(screen.getAllByTestId('operations-entry').count).eql(20);

  await t.scrollIntoView(screen.getAllByTestId('operations-entry').nth(19));

  await t.expect(screen.getAllByTestId('operations-entry').count).eql(40);
});

test('Retry and Cancel single instance ', async (t) => {
  const {initialData} = t.fixtureCtx;
  const instance = initialData.singleOperationInstance;

  // filter by instance id
  await t.typeText(
    screen.queryByRole('textbox', {
      name: 'Instance Id(s) separated by space or comma',
    }),
    instance.processInstanceKey,
    {paste: true}
  );

  // wait for filter to be applied
  await t
    .expect(
      within(screen.queryByTestId('instances-list')).getAllByRole('row').count
    )
    .eql(1);

  // retry single instance using operation button
  await t.click(
    screen.queryByRole('button', {
      name: `Retry Instance ${instance.processInstanceKey}`,
    })
  );

  // expect spinner to show and disappear
  await t.expect(screen.queryByTestId('operation-spinner').exists).ok();
  await t.expect(screen.queryByTestId('operation-spinner').exists).notOk();

  // cancel single instance using operation button
  await t
    .click(
      screen.queryByRole('button', {
        name: `Cancel Instance ${instance.processInstanceKey}`,
      })
    )
    .click(screen.getByRole('button', {name: 'Apply'}));

  await t
    .expect(screen.queryByTestId('operations-list').visible)
    .notOk()
    .click(screen.queryByRole('button', {name: 'Expand Operations'}))
    .expect(screen.queryByTestId('operations-list').visible)
    .ok();

  const operationItem = within(screen.queryByTestId('operations-list'))
    .getAllByRole('listitem')
    .nth(0);
  const operationId = await within(operationItem).queryByTestId('operation-id')
    .innerText;
  await t.expect(within(operationItem).queryByText('Cancel').exists).ok();

  // wait for instance to disappear from instances list
  await t
    .expect(
      screen.queryByText('There are no Instances matching this filter set')
        .exists
    )
    .ok();

  // wait for instance to finish in operation list (end time is present)
  await t.expect(within(operationItem).queryByText(DATE_REGEX).exists).ok();

  await t.click(within(operationItem).queryByText('1 Instance'));

  // wait for filter to be applied
  await t
    .expect(
      within(screen.queryByTestId('instances-list')).getAllByRole('row').count
    )
    .eql(1);

  const operationIdField = IS_NEW_FILTERS_FORM
    ? cmOperationIdField
    : screen.queryByRole('textbox', {
        name: 'Operation Id',
      });

  // expect operation id filter to be set
  await t.expect(operationIdField.value).eql(operationId);

  const instanceRow = within(
    within(screen.queryByTestId('instances-list')).getAllByRole('row').nth(0)
  );
  await t
    .expect(
      instanceRow.queryByTestId(`CANCELED-icon-${instance.processInstanceKey}`)
        .exists
    )
    .ok()
    .expect(
      instanceRow.queryByTestId(`ACTIVE-icon-${instance.processInstanceKey}`)
        .exists
    )
    .notOk()
    .expect(instanceRow.queryByText(instance.bpmnProcessId).exists)
    .ok()
    .expect(instanceRow.queryByText(instance.processInstanceKey).exists)
    .ok();
});

test('Retry and cancel multiple instances ', async (t) => {
  const {initialData} = t.fixtureCtx;
  const instances = initialData.batchOperationInstances.slice(0, 5);
  const instancesListItems = within(
    screen.queryByTestId('operations-list')
  ).getAllByRole('listitem');

  // filter by instance ids
  await t.typeText(
    screen.queryByRole('textbox', {
      name: 'Instance Id(s) separated by space or comma',
    }),
    // @ts-ignore I had to use ignore instead of expect-error here because Testcafe would not run the tests with it
    instances.map((instance) => instance.processInstanceKey).join(','),
    {paste: true}
  );

  const instancesList = screen.queryByTestId('instances-list');

  // wait for the filter to be applied
  await t
    .expect(within(instancesList).getAllByRole('row').count)
    .eql(instances.length);

  await t.click(
    screen.queryByRole('checkbox', {
      name: 'Select all instances',
    })
  );

  await t.click(
    screen.queryByRole('button', {
      name: `Apply Operation on ${instances.length} Instances...`,
    })
  );

  await t
    .click(
      within(screen.queryByTestId('menu')).queryByRole('button', {
        name: 'Retry',
      })
    )
    .expect(screen.queryByTestId('operations-list').visible)
    .notOk()
    .click(screen.queryByRole('button', {name: 'Apply'}))
    .expect(screen.queryByTestId('operations-list').visible)
    .ok()
    .expect(screen.getAllByTitle(/has scheduled operations/i).count)
    .eql(instances.length);

  // expect first operation item to have progress bar
  await t
    .expect(
      within(instancesListItems.nth(0)).queryByTestId('progress-bar').exists
    )
    .ok();

  // wait for instance to finish in operation list (end time is present, progess bar gone)
  await t
    .expect(within(instancesListItems.nth(0)).queryByText(DATE_REGEX).exists)
    .ok()
    .expect(
      within(instancesListItems.nth(0)).queryByTestId('progress-bar').exists
    )
    .notOk();

  // reset filters
  await t
    .click(screen.queryByRole('button', {name: /reset filters/i}))
    .expect(within(instancesList).getAllByRole('row').count)
    .gt(instances.length);

  const operationIdField = IS_NEW_FILTERS_FORM
    ? cmOperationIdField
    : screen.queryByRole('textbox', {
        name: 'Operation Id',
      });

  // select all instances from operation
  await t
    .click(
      within(instancesListItems.nth(0)).queryByText(
        `${instances.length} Instances`
      )
    )
    .expect(within(instancesList).getAllByRole('row').count)
    .eql(instances.length)
    .expect(operationIdField.value)
    .eql(
      await within(instancesListItems.nth(0)).queryByTestId('operation-id')
        .innerText
    );

  // check if all instances are shown
  await Promise.all(
    instances.map(
      // @ts-ignore I had to use ignore instead of expect-error here because Testcafe would not run the tests with it
      async (instance) =>
        await t
          .expect(
            within(instancesList).queryByText(instance.processInstanceKey)
              .exists
          )
          .ok()
    )
  );

  await t.click(
    screen.queryByRole('checkbox', {
      name: 'Select all instances',
    })
  );

  await t.click(
    screen.queryByRole('button', {
      name: `Apply Operation on ${instances.length} Instances...`,
    })
  );

  await t
    .click(
      within(screen.queryByTestId('menu')).queryByRole('button', {
        name: 'Cancel',
      })
    )
    .click(screen.queryByRole('button', {name: 'Apply'}))
    .expect(screen.queryByTestId('operations-list').visible)
    .ok()
    .expect(screen.getAllByTitle(/has scheduled operations/i).count)
    .eql(instances.length);

  // expect first operation item to have progress bar
  await t
    .expect(
      within(instancesListItems.nth(0)).queryByTestId('progress-bar').exists
    )
    .ok();

  // expect cancel icon to show for each instance
  await Promise.all(
    // @ts-ignore I had to use ignore instead of expect-error here because Testcafe would not run the tests with it
    instances.map(async (instance) =>
      t
        .expect(
          screen.queryByTestId(`CANCELED-icon-${instance.processInstanceKey}`)
            .exists
        )
        .ok({timeout: 30000})
    )
  );
});
