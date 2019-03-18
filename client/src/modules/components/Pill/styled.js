/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */

import styled, {css} from 'styled-components';

import {Colors, themed, themeStyle} from 'modules/theme';

import {ReactComponent as ClockIcon} from 'modules/components/Icon/clock.svg';

const iconStyles = css`
  margin-right: 4px;
`;

export const Clock = styled(ClockIcon)`
  ${iconStyles};
`;

const setColors = (active, dark, light) => css`
  ${({isActive}) => (isActive ? active : themeStyle({dark, light}))};
`;
const setHoverColors = (dark, light) => css`
  ${({isActive}) => !isActive && themeStyle({dark, light})};
`;

export const Count = styled.span`
  height: 16px;
  min-width: 21px;
  padding: 0px 5px;
  margin-left: 9px;
  border-radius: 8px;
  line-height: 16px;

  text-align: center;

  color: #fff;
`;

export const Pill = themed(styled.button`
  display: flex;
  align-items: center;

  ${({grow}) => {
    return grow && 'width: 100%;';
  }}

  border-radius: 16px;
  font-size: 13px;
  padding: ${({type}) => {
    return type === 'FILTER' ? '3px 3px 3px 10px' : '3px 10px';
  }};
  color: ${setColors('#ffffff', '#ffffff', Colors.uiDark05)};

  border-style: solid;
  border-width: 1px;
  border-color: ${setColors(
    Colors.primaryButton01,
    Colors.uiDark06,
    Colors.uiLight03
  )};

  background: ${setColors(
    Colors.selections,
    Colors.uiDark05,
    Colors.uiLight05
  )};

  ${Count} {
    background-color: ${themeStyle({
      dark: Colors.darkButton02,
      light: Colors.uiLight06
    })};

    opacity: ${themeStyle({
      dark: 1,
      light: 0.5
    })};
  }

  &:hover {
    background: ${setHoverColors(Colors.darkPillHover, Colors.lightButton01)};
    border-color: ${setHoverColors(Colors.darkButton02, Colors.lightButton02)};

    &:hover ${Count} {
      background-color: ${themeStyle({
        dark: Colors.darkButton02,
        light: Colors.lightButton01
      })};

      opacity: ${themeStyle({
        dark: 1,
        light: 0.55
      })};
    }
  }
`);

const growCss = css`
  flex-grow: 1;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  overflow: hidden;
`;

export const Label = styled.span`
  ${({grow}) => {
    return grow && growCss;
  }}
`;
